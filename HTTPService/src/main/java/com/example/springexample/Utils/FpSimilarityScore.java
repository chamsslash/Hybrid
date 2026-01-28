package com.example.springexample.Utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
public class FpSimilarityScore {
    private final Gson gson = new Gson();

    // Оставим для справки, но теперь будем возвращать score от 0 до 100
    // private static final double SIMILARITY_THRESHOLD = 0.6;

    public record ClientMeta(
            String ip,
            String country,
            String city,
            String asn,
            String org,
            String visitorId,
            String components,
            String secureUUID,
            String ptr
    ) {}


    private static Map<String, Double> getComponentWeights() {
        Map<String, Double> weights = new HashMap<>();
        // --- Критически важные компоненты ---
        weights.put("canvas", 10.0); // Хеш canvas очень стабилен
        weights.put("fonts", 8.0);
        weights.put("webGlBasics", 8.0);
        weights.put("webGlExtensions", 8.0); // Хешированное поле

        // --- Важные компоненты ---
        weights.put("vendor", 5.0);
        weights.put("plugins", 5.0);
        weights.put("fontPreferences", 4.0);

        // --- Менее важные или часто меняющиеся компоненты ---
        weights.put("screenResolution", 2.0);
        weights.put("languages", 2.0);
        weights.put("timezone", 1.0);
        // ... добавьте веса для остальных полей или используйте вес по умолчанию

        return weights;
    }

    /**
     * Главный метод для вычисления схожести двух отпечатков.
     * Вычисляет итоговую оценку как взвешенное среднее схожести каждого компонента.
     *
     * @param initialFp Эталонный (первый) отпечаток.
     * @param newFp     Новый отпечаток для сравнения.
     * @return Оценка схожести от 0.0 (полностью разные) до 1.0 (полностью идентичные).
     */
    public double computeWeightedSimilarity(Map<String, Object> initialFp, Map<String, Object> newFp) {
        // Получаем настроенные веса
        Map<String, Double> weights = getComponentWeights();
        double defaultWeight = 1.0; // Вес для компонентов, не указанных в карте

        double totalWeightedScore = 0.0;
        double totalWeight = 0.0;

        // Итерируемся по ключам эталонного отпечатка
        for (String key : initialFp.keySet()) {
            // Сравниваем только те компоненты, которые есть в обоих отпечатках
            if (newFp.containsKey(key)) {
                Object initialValue = initialFp.get(key);
                Object newValue = newFp.get(key);

                // Пропускаем null или "undefined" значения
                if (initialValue == null || "undefined".equals(String.valueOf(initialValue))) {
                    continue;
                }

                double componentScore = 0.0;

                // Определяем тип компонента и вызываем соответствующий метод сравнения
                if (initialValue instanceof List) {
                    componentScore = compareLists((List<?>) initialValue, (List<?>) newValue);
                } else if (initialValue instanceof Map) {
                    // Для вложенных объектов можно вызвать рекурсивное не взвешенное сравнение
                    componentScore = compareNestedMaps((Map<?, ?>) initialValue, (Map<?, ?>) newValue);
                } else {
                    // Для простых типов (String, Number, Boolean)
                    componentScore = compareSimpleValues(initialValue, newValue);
                }

                // Получаем вес для текущего компонента
                double weight = weights.getOrDefault(key, defaultWeight);

                if (componentScore < 1.0) {
                    log.warn("Частичное или полное несовпадение компонента: [{}]. Схожесть: [{}], Вес: [{}]",
                            key, String.format("%.2f", componentScore), weight);
                }

                totalWeightedScore += componentScore * weight;
                totalWeight += weight;
            }
        }

        // Защита от деления на ноль, если общих компонентов не найдено
        if (totalWeight == 0) {
            return initialFp.isEmpty() && newFp.isEmpty() ? 1.0 : 0.0;
        }

        return totalWeightedScore / totalWeight;
    }

    /**
     * Сравнивает простые значения (String, Number, Boolean).
     * @return 1.0 если равны, 0.0 если нет.
     */
    private double compareSimpleValues(Object v1, Object v2) {
        return Objects.equals(v1, v2) ? 1.0 : 0.0;
    }

    /**
     * Сравнивает два списка без учета порядка элементов.
     * Использует коэффициент Жаккара (Jaccard Index) - размер пересечения / размер объединения.
     * @return Оценка схожести от 0.0 до 1.0.
     */
    private double compareLists(List<?> list1, List<?> list2) {
        if (list1.isEmpty() && list2.isEmpty()) return 1.0;
        if (list1.isEmpty() || list2.isEmpty()) return 0.0;

        Set<?> set1 = new HashSet<>(list1);
        Set<?> set2 = new HashSet<>(list2);
        Set<?> res = unionWildcard(set1,set2);
//        Set<?> union = new HashSet<>(set1);
//        union.addAll(set2);

        Set<?> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        if (res.isEmpty()) return 1.0;

        return (double) intersection.size() / res.size();
    }

    /**
     * Сравнивает два вложенных объекта (карты), рекурсивно вызывая нужные методы.
     * Это НЕвзвешенное сравнение для простоты.
     * @return Доля совпадающих по значению ключей.
     */
    private double compareNestedMaps(Map<?, ?> map1, Map<?, ?> map2) {
        if (map1.isEmpty() && map2.isEmpty()) return 1.0;

        // Считаем общее количество уникальных ключей в обеих картах (это будет знаменатель)
        Set<Object> allKeys = new HashSet<>(map1.keySet());
        allKeys.addAll(map2.keySet());
        if (allKeys.isEmpty()) return 1.0;

        int matchingValues = 0;

        for (Object key : allKeys) {
            if (map1.containsKey(key) && map2.containsKey(key)) {
                Object v1 = map1.get(key);
                Object v2 = map2.get(key);
                // Если оба значения равны (простое сравнение для вложенных)
                if (Objects.equals(v1, v2)) {
                    matchingValues++;
                }
            }
        }

        // Схожесть - это количество совпавших значений, деленное на общее число ключей.
        return (double) matchingValues / allKeys.size();
    }

    /**
     * Главный метод, вычисляющий итоговый балл схожести от 0 до 100.
     */
    public double similarCheck(ClientMeta initialMeta, ClientMeta secondaryMeta) {
        double fullscore = 0.0;

        // --- Веса идентификаторов (самые важные) ---
        final double UUID_WEIGHT = 40.0;
        final double VISITOR_ID_WEIGHT = 35.0;
        final double COMPONENTS_WEIGHT = 10.0; // Максимальный балл за компоненты

        if (initialMeta.secureUUID() != null && initialMeta.secureUUID().equals(secondaryMeta.secureUUID())) {
            fullscore += UUID_WEIGHT;
        }
        if (initialMeta.visitorId() != null && initialMeta.visitorId().equals(secondaryMeta.visitorId())) {
            fullscore += VISITOR_ID_WEIGHT;
        }


        // --- Расчет балла за схожесть компонентов ---
        Map<String, Object> initialComps = gson.fromJson(initialMeta.components(), Map.class);
        Map<String, Object> postComps = gson.fromJson(secondaryMeta.components(), Map.class);

        if (initialComps != null && postComps != null) {
            double componentSimilarityRatio = this.computeWeightedSimilarity(initialComps, postComps);
            // Умножаем долю схожести на вес этого поля
            fullscore += componentSimilarityRatio * COMPONENTS_WEIGHT;
        }

        // --- Веса вспомогательных данных (менее важные) ---
        final double IP_WEIGHT = 5.0;
        final double ASN_ORG_WEIGHT = 5.0;
        final double GEO_WEIGHT = 5.0;

        if (initialMeta.ip() != null && initialMeta.ip().equals(secondaryMeta.ip())) {
            fullscore += IP_WEIGHT;
        }



            String initialASN = initialMeta.asn.split(" ")[0];
            String secASN = secondaryMeta.asn.split(" ")[0];
            String[] orgasnpart1= initialMeta.asn.split(" ");
            String asOrg1 = String.join(" ", Arrays.copyOfRange(orgasnpart1, 1, orgasnpart1.length));
            String[] orgasnpart2= secondaryMeta.asn.split(" ");
            String asOrg2 = String.join(" ", Arrays.copyOfRange(orgasnpart2, 1, orgasnpart2.length));
            if (initialASN != null && secASN != null) {
                String ptrNorm = normalize(initialMeta.ptr);
                String orgNorm = normalize(initialMeta.org);
                String ptrNorm2 = normalize(secondaryMeta.ptr);
                String orgNorm2 = normalize(secondaryMeta.org);
                double asnScore = 0;
                if(initialASN.equals(secASN)){asnScore += 3.0;}
                if((initialMeta.org.equals(asOrg1) && secondaryMeta.org.equals(asOrg2)) && (ptrNorm.contains(orgNorm)|| orgNorm.contains(ptrNorm)) && (ptrNorm2.contains(orgNorm2)|| orgNorm2.contains(ptrNorm2))){
                    asnScore +=3;

                }


                fullscore += (asnScore / 5.0) * ASN_ORG_WEIGHT;
            }




        // Геолокация
        if (initialMeta.country() != null && initialMeta.country().equals(secondaryMeta.country())) {
            double geoScore = 2.0; // Даем 2 балла за страну
            if (initialMeta.city() != null && initialMeta.city().equals(secondaryMeta.city())) {
                geoScore += 3.0; // И еще 3 за город
            }
            fullscore += (geoScore / 5.0) * GEO_WEIGHT;
        }
//        return fullscore >= SIMILARITY_THRESHOLD;
        return Math.min(fullscore, 100.0);
    }
    public static Set<?> unionWildcard(Set<?> a, Set<?> b) {
        Set<Object> result = new HashSet<>();
        result.addAll(a);
        result.addAll(b);
        return result;
    }
    String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "") // убрать .,- и т.п.
                .replaceAll("llc|inc|gmbh|ltd|bv|sas|sarl", ""); // убрать юридическую форму
    }
    public String hash(String string) {
        try {
            MessageDigest alg = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = alg.digest(string.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException algorithmException) {
            throw new RuntimeException("Не найден алгоритм хэширования SHA-256", algorithmException);
        }
    }

}

/// Теперь ваша система оценок логична, сбалансирована и готова к использованию. Вы можете устанавливать пороги (например, `score > 85` — высокая вероятность, `score > 50` — средняя) для принятия решений в вашем приложении.
