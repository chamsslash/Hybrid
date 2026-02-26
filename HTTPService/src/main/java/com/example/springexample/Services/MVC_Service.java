package com.example.springexample.Services;
import com.example.grpc.DataTransferService;
import com.example.springexample.*;
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
import java.security.SecureRandom;
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
    YandexGptService gptService;


    @GetMapping(path = "/createchatpage")
    public String GetCreateChat(Model model, HttpServletResponse response) {
        generateandputNonce(model, response);
        return "chatcreatepage";
    }

    @GetMapping(path = "/registerpage")
    public String GetRegisterPage(Model model, HttpServletResponse response) {
        generateandputNonce(model, response);
        return "register";
    }

    @GetMapping(path = "/welcome")
    public String GetWelcome(@RequestParam(value = "error",required = false)String error,HttpServletRequest request,Model model, HttpServletResponse response) {
       generateandputNonce(model, response);
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        log.info(csrfToken.getToken().toString());
        if (error != null) {
            model.addAttribute("error", error);
        }
        return "welcome";
    }
    @GetMapping(path = "/collect-fingerprint")
    public  String fpCollector(@RequestParam("return_url") String redirecturi,HttpServletResponse response,Model model){
        generateandputNonce(model,response);
        model.addAttribute("returnUrl",redirecturi);
        return "fingerpring_collector";
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
        ResponseCookie deleteAccess = ResponseCookie.from("access", "").maxAge(0).path("/").build();
        ResponseCookie deleteRefresh = ResponseCookie.from("refresh", "").maxAge(0).path("/").build();
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
//            ResponseCookie newRefreshTokenCookie = ResponseCookie.from("Refresh", newTokens.refresh())
//                    .httpOnly(true)
//                    .secure(true) // В продакшене должно быть true
//                    .path("/") // Используйте тот же путь, что и при установке
//                    .maxAge(Duration.ofDays(7))
//                    .sameSite("Strict")
//                    .build();
            ResponseCookie refreshCookie = ResponseCookie.from("refresh", newTokens.refresh())
                    .httpOnly(true)
//                .secure(true)
                    .sameSite("Strict")
                    .path("/")
                    .maxAge(Duration.ofDays(7))
                    .build();


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
    @GetMapping(path = "/authcallback")
    public String authcallbackpage(Model model,HttpServletResponse response){
        generateandputNonce(model,response);
        return "callback" ;}
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
            ResponseCookie refreshCookie = ResponseCookie.from("refresh", tokens.refresh())
                    .httpOnly(true)
//                .secure(true)
                    .sameSite("Strict")
                    .path("/")
                    .maxAge(Duration.ofDays(7))
                    .build();
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





    //затрайкатчить и залогировать и можно где асинхрон прикрутить
//    @PostMapping(path = "/Aiassist")
//    public ResponseEntity<?> AIassistantHelp(@RequestParam("TargetUserName") String targetusername,
//    @RequestParam("chat_id") String chat_id) throws Exception {
//        ChatContextService contextService = new ChatContextService(redisTemplate, chat_id);
//        Map<String, List<String>> nameMessages = new HashMap<>();
//        List<String> prompt =contextService.getFullContext();
//        if (prompt.isEmpty()){
//            return  ResponseEntity.ofNullable("THERE IS NO MESSAGES IN CHAT");
//        }
//        for (String s : prompt) {
//            JsonObject prompt_part =JsonParser.parseString(s).getAsJsonObject();
//            String username = prompt_part.get("user").toString();
//            String usermessage = prompt_part.get("message").toString();
//            nameMessages.computeIfAbsent(username, k -> new ArrayList<>()).add(usermessage);
//        }
//        String promptTemplate = "Ты — AI-ассистент в чате. Помоги составить короткий дружелюбный ответ пользователю с ником %s на его сообщение в контексте последних сообщений других участников. Обязательно упоминай %s, не отвечай самому себе, поддерживай беседу, тон вежливый и корректный, ответ краткий и по существу, не придумывай новых участников, соблюдай уважительный стиль.";
//        String promptWithNick = String.format(promptTemplate,targetusername,"@"+targetusername);
//        JsonArray jsonprompt =yandexGptService.BuildJsonPrompt(promptWithNick,nameMessages);
//        String answer = yandexGptService.GetAssistantAnswer(jsonprompt);
//        return ResponseEntity.ok(answer);
//    }
    private void generateandputNonce(Model model,HttpServletResponse response){
        String nonceId;

        try {
            nonceId  = Base64.getEncoder().encodeToString(
                    SecureRandom.getInstanceStrong().generateSeed(16));

        }catch (NoSuchAlgorithmException noSuchAlgorithmException){
            log.warn("no such alg for nonce");
            nonceId= null;
        }
        if (nonceId!=null){
            response.setHeader("Content-Security-Policy",
                    "script-src 'nonce-" + nonceId + "' 'strict-dynamic'; trusted-types default; object-src 'none'; base-uri 'none';");
            model.addAttribute("nonce",nonceId);

        }
    }
    public boolean computeLikelihood (FpSimilarityScore.ClientMeta newMeta,
                                      FpSimilarityScore.ClientMeta oldMeta,FpSimilarityScore FpUtils){

        double checkresult = FpUtils.similarCheck(oldMeta, newMeta);
        JsonArray prompt = gptService.BuildSecurityCheckPrompt(oldMeta,newMeta);
        Mono<String> securitypredict= gptService.aiSecurePredict(prompt);
        boolean conclusion;
        try {
            double res = securitypredict.map(doub->Double.valueOf((doub)+checkresult)/2).block();
            return res>=60;
        } catch (Exception e) {
            log.error("Ai security predict failed",e);
            conclusion = checkresult>=60;
            log.warn(Objects.toString(conclusion));
            return conclusion;
        }
    }

}
