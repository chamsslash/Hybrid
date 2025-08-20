//package com.example.springexample.Services;
//
//import org.springframework.beans.BeansException;
//import org.springframework.beans.factory.config.BeanDefinition;
//import org.springframework.beans.factory.support.BeanDefinitionRegistry;
//import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
//import org.springframework.context.annotation.Configuration;
//
///**
// * Убирает реактивный requestDataValueProcessor,
// * оставляя servlet-вариант от WebMvcSecurityConfiguration.
// */
//@Configuration(proxyBeanMethods = false)
//public class RemoveReactiveRequestDataValueProcessor
//        implements BeanDefinitionRegistryPostProcessor {
//
//    private static final String BEAN_NAME = "requestDataValueProcessor";
//    private static final String REACTIVE_FACTORY =
//            "org.springframework.security.config.annotation.web.reactive.WebFluxSecurityConfiguration";
//
//    @Override
//    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
//            throws BeansException {
//
//        if (registry.containsBeanDefinition(BEAN_NAME)) {
//            BeanDefinition bd = registry.getBeanDefinition(BEAN_NAME);
//
//            // проверяем, «чей» это бин
//            if (REACTIVE_FACTORY.equals(bd.getFactoryBeanName())) {
//                registry.removeBeanDefinition(BEAN_NAME);   // ← вот здесь метод доступен
//            }
//        }
//    }
//
//    @Override
//    public void postProcessBeanFactory(
//            org.springframework.beans.factory.config.ConfigurableListableBeanFactory bf)
//            throws BeansException {
//        /* no-op */
//    }
//}