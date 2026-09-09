package com.example.springexample.Services;
import com.example.grpc.DataTransferService;
import com.example.springexample.*;
import com.example.springexample.Metrics.FpCheckMetric;
import com.example.springexample.Utils.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.google.gson.reflect.TypeToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.*;
import java.lang.reflect.Type;

@Slf4j
@Controller
public class MVC_Service {


    @Autowired
    private Gson gson = new Gson();
    @Autowired
    AuthGrpc authGrpc;
    @Autowired
    TokensResolver tokensResolver;
    @Autowired
    ParsingDataService dataParser;
    @Autowired
    GeminiService gptService;
    @Autowired
    FpCheckMetric fpCheckMetric;

    /**
     * Ставить ли на куку сессии атрибут {@code Secure} (beads ybg).
     *
     * <p>Дефолт {@code false} — это текущий стенд на plain HTTP: кука с {@code Secure}
     * по HTTP браузером отбрасывается, и вход перестал бы работать вовсе. При публикации
     * по HTTPS значение переключается переменной окружения {@code COOKIE_SECURE}, чтобы
     * refresh-токен не ходил в открытом виде. Атрибуты самой куки — в {@link AuthCookies}.
     */
    @Value("${COOKIE_SECURE:false}")
    boolean cookieSecure;

    /**
     * Потолок ожидания вердикта Gemini. Четыре секунды: {@code /exchangeTokens}
     * выполняется браузером в фоне, и задержка сверх нескольких секунд неотличима для
     * пользователя от зависшей вкладки; типичный ответ {@code gemini-2.5-flash-lite}
     * укладывается в 1–2 с.
     */
    private static final Duration AI_VERDICT_TIMEOUT = Duration.ofSeconds(4);


    // Корень отдаёт редирект на точку входа (beads 9kn). Маппинга на "/" не было вовсе, и
    // пользователь, набравший голый хост, упирался в сырую страницу Tomcat «HTTP Status 404».
    // Именно редирект, а не рендер той же вьюхи: /welcome сам кладёт в модель nonce и CSRF —
    // дублировать эту подготовку во второй точке означало бы два места, которые обязаны
    // расходиться синхронно. "/" уже числится публичным в MvcJwtAuthFilter.PUBLIC_PATHS,
    // так что редирект отрабатывает и для анонима.
    @GetMapping(path = "/")
    public String GetRoot() {
        return "redirect:/welcome";
    }

    @GetMapping(path = "/createchatpage")
    public String GetCreateChat() {
        return "redirect:/reactive/createchat";
    }

    @GetMapping(path = "/registerpage")
    public String GetRegisterPage(Model model, HttpServletResponse response) {
        generateandputNonce(model, response);
        return "app";
    }

    @GetMapping(path = "/welcome")
    public String GetWelcome(HttpServletRequest request,Model model, HttpServletResponse response) {
       generateandputNonce(model, response);
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        log.info(csrfToken.getToken().toString());
        return "app";
    }
    @PostMapping("/exchangeTokens")
    public ResponseEntity<?> provideNewTokens(
            @RequestParam("FpComponents")String fpparts,
            @RequestHeader(value ="X-Fingerprint")String fingerprint,
            @RequestHeader(value = "X-Client-Meta")String clientMetaJson,
            @RequestHeader(value = "X-SecureUUID")String secureUUID,
            @CookieValue(value = "refresh", required = false) String refreshToken) {

        // 1. Единая, чистая валидация входных данных
        if (!StringUtils.hasText(refreshToken)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Отсутствует refresh token"));
        }
        if (!StringUtils.hasText(fingerprint)) {
            // Возвращаем ошибку 400, а не редирект
            return ResponseEntity
                    .badRequest() // Статус 400 Bad Request
                    .body(Map.of("error", "Отсутствует обязательный заголовок X-Fingerprint."));
        }
        // Backward-compat: clear legacy access cookie (we no longer use access-in-cookie).
        ResponseCookie deleteAccess = AuthCookies.deleteLegacyAccess();
        ResponseCookie deleteRefresh = AuthCookies.deleteRefresh();
        MvcJwtAuthFilter.jwt_refresh_auths newTokens;
        try {
            FpSimilarityScore FpUtils = new FpSimilarityScore();
            JsonObject jsonObject = gson.fromJson(clientMetaJson, JsonObject.class);
            String resultJson = dataParser.JsonStreamingParsing(fpparts,FpUtils);
            String ptr=  new ReverseDnsResolver().getPTR(jsonObject.get("ip").getAsString());

            jsonObject.addProperty("ptr",ptr);
            jsonObject.addProperty("secureUUID",secureUUID);
            jsonObject.addProperty("visitorId", fingerprint);
            jsonObject.addProperty("components",resultJson);
            // 2. Делегируем всю сложную логику сервис
            FpSimilarityScore.ClientMeta newMeta = gson.fromJson(jsonObject, FpSimilarityScore.ClientMeta.class);

            try {
                newTokens = tokensResolver.rotateTokens(refreshToken,newMeta);

            }catch (TokenException e){
                log.error("Error:",e);
                return  ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("redirectUrl","/welcome"));
            }

            // 3. Формируем новую HttpOnly cookie
            ResponseCookie refreshCookie = AuthCookies.refresh(newTokens.refresh(), cookieSecure);


            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,refreshCookie.toString())
                    .body(Map.of(
                            "accessToken", newTokens.jwt(),
                            "tokenType", "Bearer"
                    ));

        } catch (TokenException e) { // Ловим КОНКРЕТНОЕ кастомное исключение
            log.warn("Попытка обновить токен с невалидными данными: {}", e.getMessage());

            // 6. Формируем ответ с ошибкой и УДАЛЯЕМ старую cookie



            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, deleteAccess.toString())
                    .header(HttpHeaders.SET_COOKIE, deleteRefresh.toString())
                    .body(Map.of("error", "Сессия недействительна. Пожалуйста, войдите снова."));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
//    @GetMapping(path="proccessAccess")
//    public ResponseEntity
//            <Map<String, String>> InstallAccess(@RequestParam("accessToken") String accesscode){
//        String access  = tokensResolver.exchangeCodeOnAccess(accesscode);
//        if (Objects.isNull(access)){
//            return ResponseEntity.badRequest().body(Map.of("redirectUrl","http://localhost:2009/welcome"));
//        }
//
//        return ResponseEntity.ok()
//                .header(HttpHeaders.SET_COOKIE, rc.toString())
//                .body(Map.of("text", "Successfully established access cookie"));
//    }
    // OAuth-callback обслуживается тем же app-shell'ом, что и остальные экраны (beads j35).
    // Раньше здесь отдавался отдельный Thymeleaf-документ callback.html, и accessToken,
    // положенный после /verifylogin в память, терялся при уходе на redirectUri — смена
    // документа. Внутри шелла этот переход делает клиентский роутер, документ остаётся тем
    // же, и лишний /exchangeTokens на восстановление токена больше не нужен.
    @GetMapping(path = "/authcallback")
    public String authcallbackpage(Model model,HttpServletResponse response){
        generateandputNonce(model,response);
        return "app" ;}
    @PostMapping(path = "/verifylogin")
    public ResponseEntity<?> verifylogin(@RequestParam("code") String token, @RequestParam("state")String state,@RequestParam("FpComponents")String fpparts,
                                         @RequestHeader(value ="X-Fingerprint")String fingerprint,
                                         @RequestHeader(value = "X-Client-Meta")String clientMetaJson,
                                         @RequestHeader(value = "X-SecureUUID")String secureUUID, HttpServletResponse response,HttpServletRequest request) throws IOException {
        log.info("ПОЛУЧЕНА СЫРАЯ СТРОКА FPCOMPONENTS >>>{}<<<", fpparts);
        try {
            String oldfingerprint=tokensResolver.getFingerPrintBind(state);
            FpSimilarityScore FpUtils =new FpSimilarityScore();

            JsonObject jsonObject = gson.fromJson(clientMetaJson, JsonObject.class);
            String ptr=  new ReverseDnsResolver().getPTR(jsonObject.get("ip").getAsString());
//            JsonObject Parts = gson.fromJson(fpparts,JsonObject.class);
//        String geom = stateResolver.hash(Parts.get("canvas").getAsJsonObject().getAsJsonObject("value").get("geometry").getAsString());
//            JsonObject canvas = Parts.get("canvas").getAsJsonObject();
//            JsonObject value = canvas.get("value").getAsJsonObject();
//            String geometryElement = value.get("geometry").getAsString();
//            String textElement = value.get("text").getAsString();
//            String texthash =FpUtils.hash(textElement);
//            String geomhash =FpUtils.hash(geometryElement);
//            value.remove("geometry");
//            value.addProperty("geometry",geomhash);
//            value.addProperty("text",texthash);
            String resultJson = dataParser.JsonStreamingParsing(fpparts,FpUtils);
            jsonObject.addProperty("ptr",ptr);
            jsonObject.addProperty("secureUUID",secureUUID);
            jsonObject.addProperty("visitorId", fingerprint);
            jsonObject.addProperty("components",resultJson);

            FpSimilarityScore.ClientMeta oldMeta = gson.fromJson(oldfingerprint, FpSimilarityScore.ClientMeta.class);
            FpSimilarityScore.ClientMeta newMeta = gson.fromJson(jsonObject, FpSimilarityScore.ClientMeta.class);

            if (!computeLikelihood(newMeta,oldMeta,FpUtils)){
                log.info("fp error check");
                return ResponseEntity.status(HttpStatus.SEE_OTHER)
                        .header(HttpHeaders.LOCATION,"/welcome").build();
            }
            DataTransferService.Sub_Role subRole = authGrpc.exchangeOneTimeToken(token);



            MvcJwtAuthFilter.jwt_refresh_auths tokens = tokensResolver.genPairOfToken(subRole,newMeta);
//            String bindingToken = tokensResolver.getBindingToken(subRole.getSub());
            ResponseCookie refreshCookie = AuthCookies.refresh(tokens.refresh(), cookieSecure);
            Map<String, String> responseBody = Map.of(
                    "redirectUri", "/reactive/chatlist",
                    "accessToken", tokens.jwt(),
                    "tokenType", "Bearer"
            );
            // 4. Собираем финальный ответ
            return ResponseEntity.status(HttpStatus.OK)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(responseBody);


        }catch (Exception e){
            log.error("verify exception",e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.LOCATION,"/welcome").build();


        }



    }


//    @GetMapping(path = "/authcallback")
//    public ResponseEntity<?> AuthCallback(@RequestParam("access") String token, @RequestParam("id") String id, @RequestParam("name") String name) {
//        String decoded_token = URLDecoder.decode(token, StandardCharsets.UTF_8);
//        JsonObject obj = JsonParser.parseString(decoded_token).getAsJsonObject();
//
//
//        return ResponseEntity.status(HttpStatus.FOUND)
//                    .header(HttpHeaders.LOCATION, "/reactive/chatlist")
//                .header(HttpHeaders.SET_COOKIE, responseCookie.toString())
//                .build();
//    }





    // Генерация nonce и текст заголовка — в CspNonce, общем с реактивной половиной:
    // шаблон у них один (app.html), политика обязана быть одна и та же.
    private void generateandputNonce(Model model,HttpServletResponse response){
        String nonceId = CspNonce.generate();
        if (nonceId!=null){
            response.setHeader(CspNonce.HEADER, CspNonce.headerValue(nonceId));
            model.addAttribute("nonce",nonceId);

        }
    }
    /**
     * Сравнивает два отпечатка браузера и решает, тот ли это пользователь.
     *
     * <p><b>Метод не выбрасывает исключений — это его контракт (beads kz6).</b> Он стоит
     * на пути аутентификации в двух местах: на Google-логине ({@code MVC_Service:228}) и
     * на обновлении токена ({@code TokensResolver:220}). Второй вызывающий исключения не
     * гасит — {@code CheckRefreshAndGetSub} логирует и делает {@code throw e}, — поэтому
     * любое исключение отсюда становилось <b>500 на {@code /exchangeTokens}</b>: живой
     * пользователь внутри приложения терял сессию и уезжал на {@code /welcome}.
     *
     * <p>Раньше в {@code try} стоял только {@code .block()}, а три строки выше — расчёт
     * эвристики, построение промпта и сам вызов Gemini — стояли вне его. Мимо фолбэка
     * улетали: обрыв JSON в {@code similarCheck} (beads uok), NPE на незаполненных сетевых
     * полях и синхронный бросок {@code requireApiKey()} при пустом {@code GEMINI_API_KEY}.
     *
     * <p><b>Почему два раздельных try, а не один расширенный.</b> Фолбэк AI-ветки — это
     * {@code checkresult >= 60}, а {@code checkresult} даёт {@code similarCheck}. Занеси
     * её в тот же {@code try} — и в ветке отказа фолбэка не существует, потому что упало
     * ровно то, что им является. Поэтому исходов три, а не два:
     * <ul>
     *   <li>вердикт получен — {@code (AI + эвристика) / 2 >= 60}, как и было;</li>
     *   <li>AI не ответил — решаем одной эвристикой (задуманная деградация);</li>
     *   <li>эвристика упала — вердикта нет, <b>fail-closed</b>.</li>
     * </ul>
     *
     * <p>Fail-closed здесь недорог: отказ не уничтожает сессию — {@code rotateTokens:124}
     * кидает {@code FORBIDDEN "NotSimilar"}, запись в Redis остаётся жива, другие
     * устройства не разлогиниваются. Цена — один повторный вход. Fail-open отвергнут:
     * проверка отпечатка — единственный барьер против украденной refresh-куки с чужой
     * машины, и открывать его при внутренней ошибке значит превращать любой баг парсинга
     * в обход защиты.
     *
     * <p>Оба нештатных исхода идут в {@link FpCheckMetric}: тихий fail-closed выглядит для
     * пользователя как «меня иногда разлогинивает» и не расследуется.
     */
    public boolean computeLikelihood (FpSimilarityScore.ClientMeta newMeta,
                                      FpSimilarityScore.ClientMeta oldMeta,FpSimilarityScore FpUtils){
        double checkresult;
        try {
            checkresult = FpUtils.similarCheck(oldMeta, newMeta);
        } catch (Exception e) {
            log.error("Fingerprint heuristic failed — verdict unavailable", e);
            fpCheckMetric.verdictUnavailable();
            return false;
        }

        try {
            GeminiPrompt prompt = gptService.BuildSecurityCheckPrompt(oldMeta, newMeta);
            // probability приходит строкой "0".."100"; среднее между эвристикой и AI-оценкой.
            // block(Duration) вместо block(): /exchangeTokens браузер выполняет в фоне, и
            // неограниченное ожидание внешнего HTTP держало бы запрос сколько угодно.
            // Таймаут даёт IllegalStateException — его ловит этот же catch, отдельная
            // ветка не нужна.
            Double res = gptService.aiSecurePredict(prompt)
                    .map(doub -> (Double.parseDouble(doub.trim()) + checkresult) / 2)
                    .block(AI_VERDICT_TIMEOUT);
            if (res == null) {
                // Пустой Mono — вердикта AI нет; распаковка null дала бы NPE уже вне try.
                throw new IllegalStateException("Ai security predict returned empty result");
            }
            return res >= 60;
        } catch (Exception e) {
            log.warn("Ai security predict failed, falling back to heuristic", e);
            fpCheckMetric.aiDegraded();
            return checkresult >= 60;
        }
    }

}
