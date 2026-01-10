package com.example.springexample;

import com.example.springexample.Utils.ReverseDnsResolver;
import com.example.springexample.Utils.StateResolver;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class HttpService {

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    StateResolver stateResolver;

    @Autowired
    ResolverForRefreshTokens reqresolver;

    @Autowired
    private AuthorizationRequestRepository<OAuth2AuthorizationRequest> authrepo;

    private RedisTemplate<Object, Object> redisTemplate;
    private final Gson gson = new Gson();

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

    @PostMapping("/startauth")
    public ResponseEntity<?> BindFP(
        @RequestHeader(value = "X-Fingerprint") String fingerprint,
        @RequestHeader(value = "X-Client-Meta") String clientMetaJson,
        @RequestHeader(value = "X-SecureUUID") String secureUUID,
        @RequestParam(value = "FpComponents") String fpparts,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws Exception {
        log.info("ПОЛУЧЕНА СЫРАЯ СТРОКА FPCOMPONENTS >>>{}<<<", fpparts);
        HttpServletRequestWrapper fakeRequest = new HttpServletRequestWrapper(
            request
        ) {
            private final String FAKE_URI = "/oauth2/authorization/google";

            @Override
            public String getRequestURI() {
                return FAKE_URI;
            }

            @Override
            public String getServletPath() {
                return FAKE_URI;
            }
        };
        JsonObject json = gson.fromJson(clientMetaJson, JsonObject.class);
        //        String state = stateResolver.bindFingerPrint(stateResolver.hash(request.getHeader("X-Fingerprint"),fpparts));
        String ptr = new ReverseDnsResolver().getPTR(
            json.get("ip").getAsString()
        );
        //        JsonObject Parts = gson.fromJson(fpparts,JsonObject.class);
        ////        String geom = stateResolver.hash(Parts.get("canvas").getAsJsonObject().getAsJsonObject("value").get("geometry").getAsString());
        //        JsonObject canvas = Parts.get("canvas").getAsJsonObject();
        //        JsonObject value = canvas.get("value").getAsJsonObject();
        //        String geometryElement = value.get("geometry").getAsString();
        //        String textElement = value.get("text").getAsString();
        //        String geomhash =stateResolver.hash(geometryElement);
        //        String texhash =stateResolver.hash(textElement);
        //
        ////        value.remove("geometry");
        //        value.addProperty("geometry",geomhash);
        //        value.addProperty("text",texhash);
        //
        ////        value.remove("winding");
        //        String resultJson = gson.toJson(Parts);
        String resultJson;
        try {
            resultJson = stateResolver.JsonStreamingParsing(fpparts);
        } catch (Exception e) {
            log.error("cannot parse fp components", e);
            resultJson = null;
        }
        json.addProperty("visitorId", fingerprint);
        json.addProperty("components", resultJson);
        json.addProperty("secureUUID", secureUUID);
        json.addProperty("ptr", ptr);
        ClientMeta meta = gson.fromJson(json, ClientMeta.class);

        String state = stateResolver.bindFingerPrint(meta);
        OAuth2AuthorizationRequest oAuth2AuthorizationRequest =
            reqresolver.resolve(fakeRequest, "google");
        OAuth2AuthorizationRequest customrequest =
            reqresolver.createAuthorizationRequest(
                request,
                oAuth2AuthorizationRequest,
                state
            );
        authrepo.saveAuthorizationRequest(customrequest, request, response);
        return ResponseEntity.status(HttpStatus.OK).body(
            Map.of("redirectUrl", customrequest.getAuthorizationRequestUri())
        );
    }
}
