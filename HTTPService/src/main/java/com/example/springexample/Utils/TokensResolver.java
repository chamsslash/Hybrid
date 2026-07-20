package com.example.springexample.Utils;

import com.example.grpc.DataTransferService;
import com.example.springexample.MvcJwtAuthFilter;
import com.example.springexample.Services.AuthGrpc;
import com.example.springexample.Services.MVC_Service;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
import javax.crypto.SecretKey;

@Component
@Slf4j
@RequiredArgsConstructor
public class TokensResolver {
    private record RefreshSubject(String sub, String role, String sid) {}
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

    // Refresh tokens use HMAC-SHA512. The legacy signWith(SignatureAlgorithm.HS512, String)
    // treated REFRESH_SECRET as a Base64-encoded key, so we derive the SecretKey from the
    // Base64-decoded bytes here and use this single method for BOTH signing and verification
    // to keep previously issued refresh tokens valid.
    private SecretKey refreshKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(REFRESH_SECRET));
    }
    private String buildAccessToken(DataTransferService.Sub_Role subRole, String accessJti, String sid) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        Date now = new Date();
        Date validity = new Date(now.getTime() + ACCESS_EXPIRE);
        return Jwts.builder()
                .setSubject(subRole.getSub())
                .setId(accessJti)
                .claim("sid", sid)
                .claim("authorities", Collections.singletonList(new SimpleGrantedAuthority(subRole.getRole())))
                .claim("token_use", "access")
                .setIssuedAt(now)
                .setExpiration(validity)
                .setIssuer("Hybrid-Http-Service")
                .signWith((PrivateKey) loadKeys().get("private_key"), Jwts.SIG.RS512)
                .compact();
    }

    private String buildRefreshToken(String sub, String refreshJti, String sid) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + REFRESH_EXPIRE);
        return Jwts.builder()
                .setSubject(sub)
                .setId(refreshJti)
                .claim("sid", sid)
                .claim("token_use", "refresh")
                .setIssuedAt(now)
                .setExpiration(validity)
                .setIssuer("Hybrid-Http-Service")
                .signWith(refreshKey(), Jwts.SIG.HS512)
                .compact();
    }

    public MvcJwtAuthFilter.jwt_refresh_auths genPairOfToken(DataTransferService.Sub_Role subRole, FpSimilarityScore.ClientMeta newMeta)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();

        String newAccess = buildAccessToken(subRole, accessJti, sid);
        String newRefresh = buildRefreshToken(subRole.getSub(), refreshJti, sid);

        RefreshSession session = new RefreshSession(
                subRole.getSub(),
                sid,
                refreshJti,
                accessJti,
                newMeta,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0,
                "active"
        );
        saveSession(session);
        return new MvcJwtAuthFilter.jwt_refresh_auths(newAccess, newRefresh, subRole.getRole());
    }

    public MvcJwtAuthFilter.jwt_refresh_auths rotateTokens(String refreshToken, FpSimilarityScore.ClientMeta newMeta)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        RefreshSubject subject = this.CheckRefreshAndGetSub(refreshToken, newMeta);
        if (Objects.isNull(subject)) {
            throw new TokenException(HttpStatus.FORBIDDEN, "NotSimilar", "not similar refresh meta's");
        }
        DataTransferService.Sub_Role subRole = DataTransferService.Sub_Role.newBuilder()
                .setSub(subject.sub())
                .setRole(subject.role())
                .build();
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        RefreshSession session = getSessionBySid(subject.sid());
        if (session == null) {
            throw new TokenException(HttpStatus.FORBIDDEN, "REFRESH_SESSION_MISSING", "refresh session missing");
        }
        String newAccess = buildAccessToken(subRole, accessJti, session.getSid());
        String newRefresh = buildRefreshToken(subRole.getSub(), refreshJti, session.getSid());

        rotateSession(session, refreshJti, accessJti, newMeta);
        return new MvcJwtAuthFilter.jwt_refresh_auths(newAccess, newRefresh, subRole.getRole());
    }
    public String saveAccess(String access){
        UUID exCode = UUID.randomUUID();
        redisTemplate.opsForValue().set("Access:"+exCode,access,15,TimeUnit.MINUTES);
        return exCode.toString();
    }
    @SneakyThrows
    public   Map<String,Object> loadKeys() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        // Read keys from environment; fail fast if missing.
        String public_key = System.getenv("JWT_PUBLIC_KEY_PEM");
        String private_key = System.getenv("JWT_PRIVATE_KEY_PEM");

        if (public_key == null || private_key == null) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PEM / JWT_PRIVATE_KEY_PEM are not set");
        }
        // Handle \n escaped PEM from .env
        public_key = public_key.replace("\\n", "\n");
        private_key = private_key.replace("\\n", "\n");
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
    private RefreshSubject CheckRefreshAndGetSub(String refreshtoken, FpSimilarityScore.ClientMeta newMeta)  {
        try {
            Claims claims = Jwts.parser().verifyWith(refreshKey()).build().parseSignedClaims(refreshtoken).getPayload();
            String sub = claims.getSubject();
            Date expiration = claims.getExpiration();
            String refreshJti = claims.getId();
            String sid = claims.get("sid", String.class);

            if (!StringUtils.hasText(sid)) {
                throw new TokenException(HttpStatus.FORBIDDEN, "REFRESH_TOKEN_INVALID", "Missing sid in refresh token");
            }

            DataTransferService.User user = authGrpc.getUserBySub(sub);
            if (user == null){
                log.error("User with this sub was not found");
                deleteSessionBySid(sid);
                throw new TokenException(HttpStatus.NOT_FOUND,"REFRESH_TOKEN_DATA_UNSYNC-ED","Юзер с таким айди как в токене не найден  -->удаляю куку,запись в редисе,отправляю на авторизацию");
            }

            String jsoned_refreshsession = redisTemplate.opsForValue().get(this.generateSessionKey(sid));
            if (!StringUtils.hasText(jsoned_refreshsession)){
                log.info("Refresh session was not found");
                this.deleteAllSessionsByUser(sub);
                throw new TokenException(HttpStatus.FORBIDDEN,"REFRESH_TOKEN_DATA_UNSYNC-ED","ТОКЕН не найден на бекенде(возможно атака) -->удаляю куку,отправляю на авторизацию");
            }

            RefreshSession refreshSession = gson.fromJson(jsoned_refreshsession, RefreshSession.class);
            if (!Objects.equals(refreshJti, refreshSession.getRefreshJti())) {
                log.warn("Refresh token reuse detected");
                deleteSessionBySid(sid);
                throw new TokenException(HttpStatus.FORBIDDEN, "REFRESH_TOKEN_REUSED", "Refresh token reuse detected");
            }
            if (expiration.before(new Date())){
                log.warn("RefreshToken expired");
                deleteSessionBySid(sid);
                throw new TokenException(HttpStatus.FORBIDDEN,"REFRESH_TOKEN_EXPIRED","Срок действия токена истек --> удаляю куку,отправляю на авторизацию");
            }
            FpSimilarityScore.ClientMeta old_meta = refreshSession.meta;

            if (!mvcService.computeLikelihood(newMeta,old_meta,new FpSimilarityScore())){
                log.info("fp error check");
                return null;
            }

            return new RefreshSubject(sub, user.getRole(), sid);
        } catch (Exception e){
            log.warn("refreshtoken parse processed unsucceesfully");
            throw e;
        }
    }

    private String generateSessionKey(String sid){
        return "RefreshSession:"+sid;
    }
    private String generateUserSessionsSetKey(String sub){
        return "user:"+sub;
    }
    private String generateBindingTokenKey(String sub){return "Binding:"+sub;}

    private void deleteAllSessionsByUser(String sub){
        Set<String> setOfRefreshTokensOfUser =redisTemplate.opsForSet().members(generateUserSessionsSetKey(sub));
        if (setOfRefreshTokensOfUser == null || setOfRefreshTokensOfUser.isEmpty()){
            log.warn("User doesnt have session at all");
            return;
        }
        List<String> allSessionKeys = new ArrayList<>(setOfRefreshTokensOfUser.stream().toList());
        allSessionKeys.add(generateUserSessionsSetKey(sub));
        redisTemplate.delete(allSessionKeys);
        log.info("All sessions by user were deleted");
    }
    private void deleteSessionBySid(String sid){
        String keyOfSession =  generateSessionKey(sid);
        String jsoned = redisTemplate.opsForValue().get(keyOfSession);
        if (StringUtils.hasText(jsoned)) {
            RefreshSession session = gson.fromJson(jsoned, RefreshSession.class);
            redisTemplate.opsForSet().remove(generateUserSessionsSetKey(session.getSub()), keyOfSession);
        }
        redisTemplate.delete(keyOfSession);
    }

    private void saveSession(RefreshSession refreshSession){
        String sessionKey = generateSessionKey(refreshSession.getSid());
        redisTemplate.opsForValue().set(sessionKey, gson.toJson(refreshSession), REFRESH_EXPIRE, TimeUnit.MILLISECONDS);
        String userSetKey = generateUserSessionsSetKey(refreshSession.getSub());
        redisTemplate.opsForSet().add(userSetKey, sessionKey);
        redisTemplate.expire(userSetKey, REFRESH_EXPIRE, TimeUnit.MILLISECONDS);
    }

    private RefreshSession getSessionBySid(String sid){
        String jsoned = redisTemplate.opsForValue().get(generateSessionKey(sid));
        if (!StringUtils.hasText(jsoned)) {
            return null;
        }
        return gson.fromJson(jsoned, RefreshSession.class);
    }

    private void rotateSession(RefreshSession session, String newRefreshJti, String newAccessJti, FpSimilarityScore.ClientMeta newMeta){
        session.setRefreshJti(newRefreshJti);
        session.setAccessJti(newAccessJti);
        session.setMeta(newMeta);
        session.setLastSeenAt(System.currentTimeMillis());
        session.setRotatedAt(System.currentTimeMillis());
        saveSession(session);
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
