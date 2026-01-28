package com.example.springexample;
import com.example.springexample.Repositories.Auth_rep;
import com.nimbusds.jose.shaded.gson.Gson;
import com.nimbusds.jose.shaded.gson.GsonBuilder;
import jakarta.servlet.ServletException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSuccessHandler  implements AuthenticationSuccessHandler {


    private final RedisTemplate<String, String> redisTemplate;



    @Override
    public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        CustomOAuth2User user =(CustomOAuth2User) authentication.getPrincipal();
        String state = request.getParameter("state");
        String sub =  getSubFromPrincipal( user);
        String onetimecode= String.valueOf(UUID.randomUUID());
        if (!sub.isEmpty() && !state.isEmpty()){
            redisTemplate.opsForValue().set("UserOneTimeCodeFastCheck" + onetimecode,sub,300, TimeUnit.SECONDS);
            String redirectUrl = "/authcallback"
                    + "?state="+state+"&code=" + URLEncoder.encode(String.valueOf(onetimecode),StandardCharsets.UTF_8);
            response.sendRedirect(redirectUrl);
            return;

        }
        //Empty authcallback resp --> 401 Not Auth-ed
        return;
    }

    private String getSubFromPrincipal(CustomOAuth2User principal) {
            String subObject = principal.getSub();
            if (subObject != null) {
                return subObject;
            }else {throw new IllegalArgumentException("no sub provided");
        }
    }}
