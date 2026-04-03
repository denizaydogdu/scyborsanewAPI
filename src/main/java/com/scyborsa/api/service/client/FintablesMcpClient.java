package com.scyborsa.api.service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesMcpConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fintables MCP (Model Context Protocol) JSON-RPC 2.0 istemcisi.
 *
 * <p>Fintables MCP sunucusuna ({@code evo.fintables.com/mcp}) JSON-RPC 2.0
 * protokolü ile bağlanarak borsa verilerine erişim sağlar.
 * Java 11 HttpClient kullanır.</p>
 *
 * <p>Desteklenen MCP tool'ları:</p>
 * <ul>
 *   <li>{@code sembol_arama} — Sembol (hisse, fon) ve aracı kurum kodu/unvan doğrulama</li>
 *   <li>{@code veri_sorgula} — Readonly SQL erişimi (en önemli tool)</li>
 *   <li>{@code dokumanlarda_ara} — Doküman havuzunda full-text arama</li>
 *   <li>{@code dokuman_chunk_yukle} — Chunk içeriklerini yükleme</li>
 *   <li>{@code finansal_beceri_yukle} — Tablo şeması (tablolar, alanlar, yorumlar) yükleme</li>
 * </ul>
 *
 * <p>OAuth 2.0 PKCE token'ı {@link FintablesMcpTokenStore} üzerinden alınır.
 * Token yoksa veya süresi dolmuşsa {@link IllegalStateException} fırlatılır.</p>
 *
 * @see FintablesMcpConfig
 * @see FintablesMcpTokenStore
 * @see com.scyborsa.api.controller.FintablesMcpAuthController
 */
@Slf4j
@Service
public class FintablesMcpClient {

    /** Fintables MCP yapılandırması. */
    private final FintablesMcpConfig config;

    /** Token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** HTTP istekleri için Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** JSON serialization/deserialization için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection ile bağımlılıkları alır ve HttpClient'ı yapılandırır.
     *
     * @param config       Fintables MCP yapılandırması
     * @param tokenStore   OAuth 2.0 token saklama bileşeni
     * @param objectMapper JSON ObjectMapper
     */
    public FintablesMcpClient(FintablesMcpConfig config,
                              FintablesMcpTokenStore tokenStore,
                              ObjectMapper objectMapper) {
        this.config = config;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Sembol arama yapar (hisse, fon, aracı kurum kodu/unvan doğrulama).
     *
     * <p>Kod veya unvan kullanmadan önce bu tool ile doğrulama yapılmalıdır.</p>
     *
     * @param kodVeUnvan aranacak sembol kodu veya unvanı (ör: "GARAN", "Garanti")
     * @return JSON-RPC result alanı (arama sonuçları)
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    public JsonNode sembolArama(String kodVeUnvan) {
        Map<String, Object> arguments = Map.of("kod_ve_unvan", kodVeUnvan);
        return callTool("sembol_arama", arguments);
    }

    /**
     * Readonly SQL sorgusu çalıştırır.
     *
     * <p>Fintables veritabanına readonly erişim sağlar. En önemli MCP tool'udur.
     * Fiyat, bilanço, oran, çarpan, temettü gibi sayısal veriler bu tool ile alınır.</p>
     *
     * @param sql     çalıştırılacak SQL sorgusu
     * @param purpose sorgunun amacı (açıklama)
     * @return JSON-RPC result alanı (sorgu sonuçları)
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    public JsonNode veriSorgula(String sql, String purpose) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("sql", sql);
        arguments.put("purpose", purpose);
        return callTool("veri_sorgula", arguments);
    }

    /**
     * Doküman havuzunda full-text arama yapar (tam parametreli versiyon).
     *
     * <p>Faaliyet raporları, KAP haberleri ve diğer metinsel içeriklerde
     * purpose, query, filter ve sayfa_basi parametreleri ile arama yapar.</p>
     *
     * @param purpose   aramanın amacı (zorunlu)
     * @param query     aranacak metin (opsiyonel, null/blank ise gönderilmez)
     * @param filter    Meilisearch filter ifadesi (opsiyonel, ör: {@code iliskili_semboller = "THYAO"})
     * @param sayfaBasi sayfa başına sonuç sayısı (max 50)
     * @return JSON-RPC result alanı (arama sonuçları)
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    public JsonNode dokumanlardaAra(String purpose, String query, String filter, int sayfaBasi) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("purpose", purpose);
        if (query != null && !query.isBlank()) {
            arguments.put("query", query);
        }
        if (filter != null && !filter.isBlank()) {
            arguments.put("filter", filter);
        }
        arguments.put("sayfa_basi", sayfaBasi);
        return callTool("dokumanlarda_ara", arguments);
    }

    /**
     * Doküman havuzunda full-text arama yapar (geriye uyumlu basit versiyon).
     *
     * <p>Faaliyet raporları, KAP haberleri ve diğer metinsel içeriklerde arama yapar.
     * İç olarak {@link #dokumanlardaAra(String, String, String, int)} metodunu çağırır.</p>
     *
     * @param query arama sorgusu
     * @return JSON-RPC result alanı (arama sonuçları)
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    public JsonNode dokumanlardaAra(String query) {
        return dokumanlardaAra(query, query, null, 8);
    }

    /**
     * Belirtilen chunk ID'lerine ait doküman içeriklerini yükler.
     *
     * @param ids yüklenecek chunk ID listesi
     * @return JSON-RPC result alanı (chunk içerikleri)
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    public JsonNode dokumanChunkYukle(List<String> ids) {
        Map<String, Object> arguments = Map.of("ids", ids);
        return callTool("dokuman_chunk_yukle", arguments);
    }

    /**
     * Finansal beceri (tablo şeması) bilgilerini yükler.
     *
     * <p>Veritabanı tablolarının alan adları, tipleri ve açıklamalarını döndürür.
     * {@code veri_sorgula} ile SQL yazmadan önce şema bilgisi almak için kullanılır.</p>
     *
     * @param skill yüklenecek beceri adı
     * @return JSON-RPC result alanı (şema bilgileri)
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    public JsonNode finansalBeceriYukle(String skill) {
        Map<String, Object> arguments = Map.of("skill", skill);
        return callTool("finansal_beceri_yukle", arguments);
    }

    /**
     * MCP sunucusuna JSON-RPC 2.0 tool çağrısı yapar.
     *
     * <p>Request format:</p>
     * <pre>{@code
     * {
     *   "jsonrpc": "2.0",
     *   "method": "tools/call",
     *   "params": {
     *     "name": "tool_name",
     *     "arguments": { ... }
     *   },
     *   "id": "uuid"
     * }
     * }</pre>
     *
     * @param toolName  çağrılacak MCP tool adı
     * @param arguments tool'a gönderilecek parametreler
     * @return JSON-RPC result alanı
     * @throws IllegalStateException token yoksa veya süresi dolmuşsa
     * @throws RuntimeException      MCP çağrısı başarısız olursa
     */
    private JsonNode callTool(String toolName, Map<String, Object> arguments) {
        if (!tokenStore.isTokenValid()) {
            throw new IllegalStateException(
                    "Fintables MCP token geçersiz veya mevcut değil. " +
                    "Önce /api/v1/fintables/auth endpoint'i ile OAuth akışını başlatın.");
        }

        String requestId = UUID.randomUUID().toString();

        Map<String, Object> rpcRequest = new HashMap<>();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("method", "tools/call");
        rpcRequest.put("params", Map.of(
                "name", toolName,
                "arguments", arguments
        ));
        rpcRequest.put("id", requestId);

        log.debug("Fintables MCP çağrısı: tool={}, id={}", toolName, requestId);

        try {
            String requestBody = objectMapper.writeValueAsString(rpcRequest);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getMcpUrl()))
                    .header("Authorization", "Bearer " + tokenStore.getAccessToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Fintables MCP HTTP hatası: tool={}, status={}, body={}",
                        toolName, response.statusCode(), response.body());
                throw new RuntimeException(
                        "Fintables MCP HTTP hatası: " + response.statusCode());
            }

            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) {
                throw new RuntimeException("Fintables MCP boş yanıt döndü: tool=" + toolName);
            }

            JsonNode rpcResponse = objectMapper.readTree(responseBody);

            // JSON-RPC hata kontrolü
            if (rpcResponse.has("error")) {
                JsonNode error = rpcResponse.get("error");
                String errorMsg = error.has("message") ? error.get("message").asText() : "Bilinmeyen hata";
                int errorCode = error.has("code") ? error.get("code").asInt() : -1;
                log.error("Fintables MCP JSON-RPC hatası: tool={}, code={}, message={}",
                        toolName, errorCode, errorMsg);
                throw new RuntimeException(
                        "Fintables MCP hatası [" + errorCode + "]: " + errorMsg);
            }

            JsonNode result = rpcResponse.get("result");
            log.debug("Fintables MCP başarılı: tool={}, id={}", toolName, requestId);
            return result;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fintables MCP çağrı hatası: tool={}", toolName, e);
            throw new RuntimeException("Fintables MCP çağrı hatası: " + e.getMessage(), e);
        }
    }
}
