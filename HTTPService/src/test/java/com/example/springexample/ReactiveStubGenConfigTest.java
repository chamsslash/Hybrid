package com.example.springexample;

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import net.devh.boot.grpc.client.channelfactory.ShadedNettyChannelFactory;
import net.devh.boot.grpc.client.config.GrpcChannelsProperties;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Доказательство для beads 16t: канал ReactiveTransferService реально получает
 * keepalive и round_robin, а не только объявляет их в application.yml мимо кода.
 *
 * Два теста бьют по двум разным точкам отказа:
 *  1. {@link #reactiveClientStubIsWiredThroughGrpcClientWithMatchingName()} —
 *     код ReactiveStubGen действительно просит канал с именем "ReactiveTransferService"
 *     у net.devh-стартера (а не собирает ManagedChannelBuilder вручную мимо конфига).
 *     Если кто-то откатит на ручную сборку канала — этот тест красный.
 *  2. {@link #realApplicationYmlProducesKeepAliveAndRoundRobinForThatChannelName()} —
 *     ТОТ ЖЕ производственный код net.devh (ShadedNettyChannelFactory.configure),
 *     прогнанный на РЕАЛЬНОМ application.yml, реально проставляет keepalive
 *     30s/10s и default-load-balancing-policy=round_robin на живом
 *     io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder — это чтение
 *     фактической конфигурации канала в рантайме, а не сравнение диффа с yml.
 *     Если блок grpc.client.ReactiveTransferService в yml сломается (опечатка
 *     в ключе, потерянный keep-alive-time и т.п.) — этот тест красный.
 *
 * Вместе они закрывают путь от аннотации в коде до реально сконфигурированного
 * канала: тест 1 проверяет, что имя клиента в коде == "ReactiveTransferService",
 * тест 2 проверяет, что под этим именем в yml лежат правильные настройки и что
 * net.devh их реально применяет к билдеру.
 */
class ReactiveStubGenConfigTest {

    @Test
    void reactiveClientStubIsWiredThroughGrpcClientWithMatchingName() throws NoSuchFieldException {
        Field field = ReactiveStubGen.class.getDeclaredField("reactiveClientStub");
        GrpcClient annotation = field.getAnnotation(GrpcClient.class);

        assertTrue(annotation != null,
                "поле reactiveClientStub должно быть помечено @GrpcClient — иначе net.devh "
                        + "не станет строить для него канал по конфигу grpc.client.ReactiveTransferService");
        assertEquals("ReactiveTransferService", annotation.value(),
                "имя клиента в @GrpcClient обязано совпадать с ключом в application.yml, "
                        + "иначе net.devh построит канал по умолчанию без keepalive и LB");
    }

    @Test
    void realApplicationYmlProducesKeepAliveAndRoundRobinForThatChannelName() throws Exception {
        GrpcChannelsProperties properties = bindRealApplicationYml();

        NettyChannelBuilder builder = configuredBuilderFor(properties, "ReactiveTransferService");

        assertEquals(Duration.ofSeconds(30).toNanos(), readLong(builder, "keepAliveTimeNanos"),
                "keep-alive-time из application.yml должен долететь до билдера канала");
        assertEquals(Duration.ofSeconds(10).toNanos(), readLong(builder, "keepAliveTimeoutNanos"),
                "keep-alive-timeout из application.yml должен долететь до билдера канала");

        Object delegate = invoke(builder, "delegate");
        assertEquals("round_robin", readField(delegate, "defaultLbPolicy"),
                "default-load-balancing-policy из application.yml должен долететь до билдера канала");
    }

    /**
     * Биндит реальный HTTPService/src/main/resources/application.yml тем же механизмом
     * (Binder + ConfigurationPropertySources), которым Spring Boot биндит
     * {@code @ConfigurationProperties(prefix = "grpc.client")} в проде — без ручного
     * дублирования значений из yml в тесте.
     */
    private static GrpcChannelsProperties bindRealApplicationYml() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        // Префикс "grpc", не "grpc.client": сама GrpcChannelsProperties биндится с
        // @ConfigurationProperties("grpc"), а её карта клиентов называется "client" —
        // это и даёт итоговый путь в yml grpc.client.<имя>.*.
        Binder binder = new Binder(ConfigurationPropertySources.from(loaded));
        return binder.bind("grpc", GrpcChannelsProperties.class)
                .orElseThrow(() -> new AssertionError("grpc не забиндился из application.yml"));
    }

    /**
     * Прогоняет РЕАЛЬНЫЙ {@code ShadedNettyChannelFactory} (тот же класс, что net.devh
     * использует для @GrpcClient-полей в проде) через его protected-шаги newChannelBuilder
     * + configure, не строя сам ManagedChannel (чтобы не тянуть реальное DNS-резолвление
     * в юнит-тесте) — configure уже применил все настройки к билдеру на этом шаге.
     */
    private static NettyChannelBuilder configuredBuilderFor(GrpcChannelsProperties properties, String name)
            throws Exception {
        ShadedNettyChannelFactory factory = new ShadedNettyChannelFactory(
                properties, new GlobalClientInterceptorRegistry(new StaticApplicationContext()), List.of());

        Method newChannelBuilder = findDeclared(ShadedNettyChannelFactory.class, "newChannelBuilder", String.class);
        Method configure = findDeclared(findConfigureOwner(factory.getClass()), "configure",
                findParamType(newChannelBuilder), String.class);

        NettyChannelBuilder builder = (NettyChannelBuilder) newChannelBuilder.invoke(factory, name);
        configure.invoke(factory, builder, name);
        return builder;
    }

    private static Class<?> findParamType(Method m) {
        return m.getReturnType();
    }

    private static Class<?> findConfigureOwner(Class<?> start) {
        Class<?> c = start;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("configure") && m.getParameterCount() == 2
                        && m.getParameterTypes()[1] == String.class) {
                    return c;
                }
            }
            c = c.getSuperclass();
        }
        throw new IllegalStateException("configure(T, String) не найден по иерархии " + start);
    }

    private static Method findDeclared(Class<?> owner, String name, Class<?>... paramTypes) throws Exception {
        Class<?> c = owner;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                    boolean matches = true;
                    Class<?>[] actual = m.getParameterTypes();
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!actual[i].isAssignableFrom(paramTypes[i]) && !paramTypes[i].isAssignableFrom(actual[i])) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(owner + "#" + name);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(target.getClass() + "#" + methodName);
    }

    private static long readLong(Object target, String fieldName) throws Exception {
        return (long) readField(target, fieldName);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(target.getClass() + "#" + fieldName);
    }
}
