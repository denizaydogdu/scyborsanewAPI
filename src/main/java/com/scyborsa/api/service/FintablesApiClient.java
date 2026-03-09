package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesApiConfig;
import com.scyborsa.api.dto.FintablesBrokerageDto;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fintables (api.fintables.com) REST API istemcisi.
 *
 * <p>OkHttp kullanarak Fintables API'ye GET istekleri gonderir.
 * Cloudflare bot korumasini asabilmek icin OkHttp tercih edilmistir
 * (Java 11 HttpClient TLS fingerprint'i Cloudflare tarafindan engellenir).</p>
 *
 * <p>Kimlik dogrulama {@code Cookie} header'i uzerinden yapilir.
 * Cookie icinde {@code auth-token=<JWT>} yer alir.</p>
 *
 * <p>Konfigurasyon {@link com.scyborsa.api.config.FintablesApiConfig} uzerinden saglanir
 * (base URL, Bearer token, cookie).</p>
 *
 * @see FintablesApiConfig
 */
@Slf4j
@Service
public class FintablesApiClient {

    private final FintablesApiConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    /**
     * Constructor injection ile bagimliliklari alir ve OkHttp client'i olusturur.
     *
     * @param config       Fintables API konfigurasyonu (base URL, Bearer token, cookie)
     * @param objectMapper JSON parse icin Jackson ObjectMapper
     */
    public FintablesApiClient(FintablesApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getRequestTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * Belirtilen URL'ye GET istegi yapar ve ham response body'sini doner.
     *
     * <p>URL tam (absolute) olabilir veya path olabilir. Path ise base URL ile birlestirilir.</p>
     *
     * @param url API URL'i (tam URL veya path)
     * @return ham JSON response body string'i
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti veya I/O hatasi durumunda
     */
    public String get(String url) throws Exception {
        String fullUrl = url.startsWith("http") ? url : config.getBaseUrl() + url;

        // SSRF korunma: sadece konfigure edilen domain'e istek yapilabilir
        URI baseUri = URI.create(config.getBaseUrl());
        URI requestUri = URI.create(fullUrl);
        if (!requestUri.getHost().equals(baseUri.getHost())) {
            throw new IllegalArgumentException("Gecersiz API URL: beklenmeyen domain");
        }

        log.debug("Fintables API GET: {}", fullUrl);

        Request.Builder requestBuilder = new Request.Builder()
                .url(fullUrl)
                .get();

        // Cookie header ile kimlik dogrulama (Cloudflare uyumlu)
        String cookie = config.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookie);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            if (statusCode != 200) {
                log.error("Fintables API hatasi: status={}, url={}", statusCode, fullUrl);
                throw new RuntimeException("Fintables API hatasi: " + statusCode);
            }

            String body = response.body() != null ? response.body().string() : "";
            return body;
        }
    }

    /**
     * Belirtilen URL'ye GET istegi yapar ve response'u belirtilen tipe deserialize eder.
     *
     * @param <T>     hedef tip
     * @param url     API URL'i (tam URL veya path)
     * @param typeRef Jackson TypeReference
     * @return deserialize edilmis response nesnesi
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti, I/O veya JSON parse hatasi durumunda
     */
    public <T> T get(String url, TypeReference<T> typeRef) throws Exception {
        String body = get(url);
        return objectMapper.readValue(body, typeRef);
    }

    /**
     * Fintables araci kurum listesini getirir.
     *
     * <p>{@code /brokerages/} endpoint'ine GET istegi yaparak
     * tum araci kurum bilgilerini dondurur.</p>
     *
     * @return araci kurum DTO listesi
     * @throws Exception baglanti, I/O veya JSON parse hatasi durumunda
     */
    public List<FintablesBrokerageDto> getBrokerages() throws Exception {
        log.debug("Fintables brokerages listesi aliniyor");
        return get("/brokerages/", new TypeReference<>() {});
    }
}
