package com.scyborsa.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scyborsa API uygulamasinin giris noktasi.
 * Spring Boot ile baslatilir ve zamanlanmis gorevler (scheduling) aktiftir.
 * REST API, WebSocket ve H2 veritabani servislerini ayaga kaldirir.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class ScyborsaApiApplication {

    /**
     * Uygulamayi baslatir.
     *
     * @param args komut satiri argumanlari
     */
    public static void main(String[] args) {
        SpringApplication.run(ScyborsaApiApplication.class, args);
    }
}
