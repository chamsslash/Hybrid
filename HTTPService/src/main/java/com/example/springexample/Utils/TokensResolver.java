package com.example.springexample.Utils;

import com.example.grpc.DataTransferService;
import com.example.springexample.MvcJwtAuthFilter;
import com.example.springexample.Services.AuthGrpc;
import com.example.springexample.Services.MVC_Service;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jsonwebtoken.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class TokensResolver {
    @Lazy
    @Autowired
    MVC_Service mvcService;
    private final AuthGrpc authGrpc;
    @Value("${securityProps.refresh-expiration-ms}")
    private long  REFRESH_EXPIRE;
    @Value("${securityProps.refresh-secret}")
    private String REFRESH_SECRET;
    @Value("${securityProps.jwt-expiration-ms}")
    private long ACCESS_EXPIRE;
    private  final Gson gson = new Gson();
    private final RedisTemplate<String,String> redisTemplate;
    private String genRefreshToken(RefreshSession refreshSession){
        Date now = new Date();
        Date validity = new Date(now.getTime() + REFRESH_EXPIRE);
        String RefreshUUID = UUID.randomUUID().toString();
        String sessionKey =  this.generateSessionKey(RefreshUUID);

        redisTemplate.opsForValue().set(sessionKey,gson.toJson(refreshSession),30*24,TimeUnit.HOURS);
        redisTemplate.opsForSet().add(generateUserSessionsSetKey(refreshSession.getSub()),sessionKey);
        return  Jwts.builder().setSubject(refreshSession.getSub()).setId(RefreshUUID).setIssuedAt(now).setExpiration(validity).claim("jwt_jti",refreshSession.getAccessId()).setIssuer("Hybrid-Http-Service").signWith(SignatureAlgorithm.HS512,REFRESH_SECRET).compact();
    }
    public MvcJwtAuthFilter.jwt_refresh_auths genPairOfToken(String refreshtoken, FpSimilarityScore.ClientMeta newMeta) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        DataTransferService.Sub_Role subRole;
        try {
            subRole =  gson.fromJson(refreshtoken, DataTransferService.Sub_Role.class);

        }
        catch (Exception e){
            subRole = this.CheckRefreshAndGetSub(refreshtoken,newMeta);
            if( Objects.isNull(subRole)){
                throw new TokenException(HttpStatus.FORBIDDEN,"NotSimilar","not similar refresh meta's");
            }
        }
        Date now = new Date();
        Date validity = new Date(now.getTime() + ACCESS_EXPIRE);
        String jti =  UUID.randomUUID().toString();
        String newAccess= Jwts.builder()
                .setSubject(subRole.getSub()).setId(jti)
                .claim("authorities",Collections.singletonList(new SimpleGrantedAuthority(subRole.getRole()))).setIssuedAt(now)
                .setExpiration(validity).setIssuer("Hybrid-Http-Service")
                .signWith(SignatureAlgorithm.RS512, (PrivateKey) loadKeys().get("private_key"))
                .compact();
        String newRefresh =  genRefreshToken(new RefreshSession(subRole.getSub(),newMeta,jti));
//        return  new HashMap<>(){{
//            put("RefreshToken",newRefresh);
//            put("JwtToken",newAccess);
//            put("Authorities",subRole.getRole());
//        }};
        return new MvcJwtAuthFilter.jwt_refresh_auths(newAccess,newRefresh,subRole.getRole());
    }
    public String saveAccess(String access){
        UUID exCode = UUID.randomUUID();
        redisTemplate.opsForValue().set("accesToken"+exCode,access,15,TimeUnit.MINUTES);
        return exCode.toString();
    }
    @SneakyThrows
    public   Map<String,Object> loadKeys() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String data = Files.readString(Paths.get("src/main/resources/mycrypt.json"));
        JsonObject jsondata = JsonParser.parseString(data).getAsJsonObject();
        String public_key = jsondata.get("public_key").getAsString();
        String private_key = jsondata.get("private_key").getAsString();
        PemObject privateKeyPem;
        PemObject publicKeyPem;
        try (PemReader privateReader = new PemReader(new StringReader(private_key));
             PemReader publicReader = new PemReader(new StringReader(public_key))) {

            privateKeyPem = privateReader.readPemObject();
            publicKeyPem = publicReader.readPemObject();
        }
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyPem.getContent());
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

        // Создаем спецификацию для приватного ключа из его "сырых" данных
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyPem.getContent());
        // Генерируем объект PrivateKey
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
        return new HashMap<>() {{
            put("public_key", publicKey);
            put("private_key",privateKey);
        }};
    }
    public String getRefreshByJti(String jti){
        String res = redisTemplate.opsForValue().get(generateSessionKey(jti));
        if(Objects.isNull(res)){
            return null;
        }else return res;
    }
    private DataTransferService.Sub_Role CheckRefreshAndGetSub(String refreshtoken, FpSimilarityScore.ClientMeta newMeta)  {
       try {
            Claims claims = Jwts.parser().setSigningKey(REFRESH_SECRET).build().parseClaimsJws(refreshtoken).getBody();
            String sub = claims.getSubject();
            Date expiration =claims.getExpiration();
            String jti = claims.getId();
            DataTransferService.User user = authGrpc.getUserBySub(sub);
            if (user == null){
                log.error("User with this sub was not found");
                redisTemplate.delete("RefreshToken:"+jti);
                throw new TokenException(HttpStatus.NOT_FOUND,"REFRESH_TOKEN_DATA_UNSYNC-ED","Юзер с таким айди как в токене не найден  -->удаляю куку,запись в редисе,отправляю на авторизацию");


            }
           String jsoned_refreshsession = redisTemplate.opsForValue().get(this.generateSessionKey(jti));
           if (jsoned_refreshsession.isEmpty()){
               log.info("Refresh was not found");
               this.deleteAllSessionsByUser(sub);
               throw new TokenException(HttpStatus.FORBIDDEN,"REFRESH_TOKEN_DATA_UNSYNC-ED","ТОКЕН не найден  на бекенде(возможно атака) -->удаляю куку,отправляю на авторизацию");

           }
           RefreshSession refreshSession = gson.fromJson(jsoned_refreshsession,RefreshSession.class);
           if (expiration.before(new Date())){
                log.warn("RefreshToken expired");
                throw new TokenException(HttpStatus.FORBIDDEN,"REFRESH_TOKEN_EXPIRED","Срок действия токена истек --> удаляю куку,отправляю на авторизацию");
           }
           FpSimilarityScore.ClientMeta old_meta = refreshSession.meta;

           if (!mvcService.computeLikelihood(newMeta,old_meta,new FpSimilarityScore())){
               log.info("fp error check");
              return null;
           }
           this.deleteOneSession(jti,sub);
        return DataTransferService.Sub_Role.newBuilder().setSub(sub).setRole(user.getRole()).build();
       }catch (Exception e){
           log.warn("refreshtoken parse processed unsucceesfully");
           throw e;
       }
    }
    private String generateSessionKey(String jti){
        return "RefreshToken:"+jti;
    }
    private String generateUserSessionsSetKey(String sub){
        return "user:"+sub;
    }
    private String generateBindingTokenKey(String sub){return "Binding:"+sub;}

    private void deleteAllSessionsByUser(String sub){
        Set<String> setOfRefreshTokensOfUser =redisTemplate.opsForSet().members(generateUserSessionsSetKey(sub));
        if (setOfRefreshTokensOfUser.isEmpty()){
            log.warn("User doesnt have session at all");
            return;
        }
        List<String> allSessionKeys = new ArrayList<>(setOfRefreshTokensOfUser.stream().toList());
        allSessionKeys.add(generateUserSessionsSetKey(sub)+sub);
        redisTemplate.delete(allSessionKeys);
        log.info("All sessions by user were deleted");
    }
    private void deleteOneSession(String jti,String sub){
        String keyOfSession =  generateSessionKey(jti);
        String userSessionsSetKey = generateUserSessionsSetKey(sub);
        redisTemplate.delete(keyOfSession);
        redisTemplate.opsForSet().remove(userSessionsSetKey,keyOfSession);
        Long size = redisTemplate.opsForSet().size(userSessionsSetKey);
        if  (size!=null && size == 0){
            log.info("User Sessions Set is empty --> Deleting");
            redisTemplate.delete(userSessionsSetKey);
        }
    }
    public String hash(String fingerPrint){
        try {
            MessageDigest alg =MessageDigest.getInstance("SHA-256");
            byte[] hashBytes=alg.digest(fingerPrint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        }catch (NoSuchAlgorithmException algorithmException){
            throw new RuntimeException("Не найден алгоритм хэширования SHA-256", algorithmException);
        }

    }
    public  String getFingerPrintBind(String state){
        String fp =redisTemplate.opsForValue().get("FingerPrint:"+state);
        if (fp==null || fp.isEmpty()){return  null;}
        return fp;
    }
    public String exchangeCodeOnAccess(String code){
        try {
            return redisTemplate.opsForValue().get("Access:"+code);

        } catch (Exception e) {
            log.warn("access token doesnt exist");
            return null;
        }
    };

    }

