package com.scyborsa.api.service.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.GateVelzonApiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gate Velzon (gate.velzon.tr) REST API istemcisi.
 *
 * <p>Java 11 {@link java.net.http.HttpClient} kullanarak Gate Velzon API'ye
 * GET istekleri gonderir. Her istekte {@code X-API-KEY} header'i eklenir.</p>
 *
 * <p>Bilanco, rasyo ve finansal rapor verilerine erisim saglar.</p>
 *
 * <p>Konfigürasyon {@link GateVelzonApiConfig} uzerinden saglanir
 * (base URL, API key, timeout degerleri).</p>
 *
 * @see GateVelzonApiConfig
 * @see com.scyborsa.api.service.bilanco.BilancoService
 */
@Slf4j
@Service
public class GateVelzonApiClient {

    /** Gate Velzon API konfigurasyonu (base URL, API key, timeout). */
    private final GateVelzonApiConfig config;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client'i olusturur.
     *
     * @param config       Gate Velzon API konfigurasyonu (base URL, API key)
     * @param objectMapper JSON parse icin Jackson ObjectMapper
     */
    public GateVelzonApiClient(GateVelzonApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Belirtilen path'e GET istegi yapar ve ham response body'sini doner.
     *
     * @param path API path (orn. "/api/bilanco/son")
     * @return ham JSON response body string'i
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti veya I/O hatasi durumunda
     */
    public String get(String path) throws Exception {
        String url = config.getBaseUrl() + path;
        log.debug("[GATE-VELZON] GET: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("X-API-KEY", config.getApiKey())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("[GATE-VELZON] API hatasi: status={}, path={}", response.statusCode(), path);
            throw new RuntimeException("[GATE-VELZON] API hatasi: " + response.statusCode());
        }

        return response.body();
    }

    /**
     * Belirtilen path'e GET istegi yapar ve response'u belirtilen tipe deserialize eder.
     *
     * @param <T>     hedef tip
     * @param path    API path (orn. "/api/bilanco/son")
     * @param typeRef Jackson TypeReference (orn. {@code new TypeReference<Map<String, Object>>() {}})
     * @return deserialize edilmis response nesnesi
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti, I/O veya JSON parse hatasi durumunda
     */
    public <T> T get(String path, TypeReference<T> typeRef) throws Exception {
        String body = get(path);
        return objectMapper.readValue(body, typeRef);
    }
}
