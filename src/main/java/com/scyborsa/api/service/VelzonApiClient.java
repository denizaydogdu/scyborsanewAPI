package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.VelzonApiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Velzon (api.velzon.tr) REST API istemcisi.
 *
 * <p>Java 11 {@link java.net.http.HttpClient} kullanarak Velzon API'ye
 * GET istekleri gönderir. Her istekte {@code X-API-KEY} header'i eklenir.</p>
 *
 * <p>Konfigürasyon {@link com.scyborsa.api.config.VelzonApiConfig} üzerinden saglanir
 * (base URL, API key).</p>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link com.scyborsa.api.config.VelzonApiConfig} - API base URL ve key</li>
 *   <li>{@link com.fasterxml.jackson.databind.ObjectMapper} - JSON deserialization</li>
 * </ul>
 *
 * @see BistStockSyncService
 * @see PineScreenerService
 */
@Slf4j
@Service
public class VelzonApiClient {

    /** Velzon API konfigurasyonu (base URL, API key, timeout). */
    private final VelzonApiConfig config;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client'i olusturur.
     *
     * @param config       Velzon API konfigürasyonu (base URL, API key)
     * @param objectMapper JSON parse icin Jackson ObjectMapper
     */
    public VelzonApiClient(VelzonApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Belirtilen path'e GET istegi yapar ve ham response body'sini döner.
     *
     * @param path API path (ör. "/api/screener/ALL_STOCKS_WITH_INDICATORS")
     * @return ham JSON response body string'i
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti veya I/O hatasi durumunda
     */
    public String get(String path) throws Exception {
        String url = config.getBaseUrl() + path;
        log.debug("Velzon API GET: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("X-API-KEY", config.getApiKey())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Velzon API hatasi: status={}, path={}", response.statusCode(), path);
            throw new RuntimeException("Velzon API hatasi: " + response.statusCode());
        }

        return response.body();
    }

    /**
     * Belirtilen path'e GET istegi yapar ve response'u belirtilen tipe deserialize eder.
     *
     * @param <T>     hedef tip
     * @param path    API path (ör. "/api/screener/ALL_STOCKS_WITH_INDICATORS")
     * @param typeRef Jackson TypeReference (ör. {@code new TypeReference<Map<String, Object>>() {}})
     * @return deserialize edilmis response nesnesi
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti, I/O veya JSON parse hatasi durumunda
     */
    public <T> T get(String path, TypeReference<T> typeRef) throws Exception {
        String body = get(path);
        return objectMapper.readValue(body, typeRef);
    }

    /**
     * Belirtilen path'e GET istegi yapar ve ham byte dizisi olarak döner.
     *
     * <p>PDF, CSV gibi binary icerikler icin kullanilir.
     * {@code Accept: &#42;/&#42;} header'i ile istek yapilir.</p>
     *
     * @param path API path (ör. "/api/funds/AAK/pdf")
     * @return ham response body byte dizisi
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti veya I/O hatasi durumunda
     */
    public byte[] getBytes(String path) throws Exception {
        String url = config.getBaseUrl() + path;
        log.debug("Velzon API GET (bytes): {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Accept", "*/*")
                .header("X-API-KEY", config.getApiKey())
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            log.error("Velzon API hatasi (bytes): status={}, path={}", response.statusCode(), path);
            throw new RuntimeException("Velzon API hatasi: " + response.statusCode());
        }

        return response.body();
    }
}
