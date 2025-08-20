package com.example.springexample.Utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.SneakyThrows;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;

@Component
public class DataResolver {
    @SneakyThrows
    @Cacheable("public_key")
    public Map<String,Object> loadKeys() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("mycrypt.json")) {

            if (inputStream == null) {
                throw new RuntimeException("Не удалось найти файл mycrypt.json в classpath");
            }

            // ИСПРАВЛЕНИЕ №2: Читаем inputStream в JsonObject
            JsonObject jsondata = JsonParser.parseReader(new InputStreamReader(inputStream)).getAsJsonObject();
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
}}
