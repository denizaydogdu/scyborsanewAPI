package com.scyborsa.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesMcpConfig;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import com.scyborsa.api.service.enrichment.*;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fintables MCP OAuth 2.0 PKCE kimlik doğrulama controller'ı.
 *
 * <p>OAuth 2.0 Authorization Code + PKCE (S256) akışını yönetir:</p>
 * <ol>
 *   <li>{@code /auth} — PKCE code_verifier+code_challenge üretir, Fintables authorize URL'ye redirect eder</li>
 *   <li>{@code /callback} — Authorization code ile token exchange yapar</li>
 *   <li>{@code /status} — Mevcut token durumunu döndürür</li>
 * </ol>
 *
 * <p>Public client olduğu için client_secret gönderilmez,
 * PKCE (S256) ile güvenlik sağlanır.</p>
 *
 * @see FintablesMcpConfig
 * @see FintablesMcpTokenStore
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fintables")
@RequiredArgsConstructor
public class FintablesMcpAuthController {

    /** Fintables MCP yapılandırması. */
    private final FintablesMcpConfig config;

    /** Token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** MCP JSON-RPC istemcisi. */
    private final FintablesMcpClient mcpClient;

    /** JSON parse için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Profil kontrol yardımcısı. */
    private final ProfileUtils profileUtils;

    /** Açığa satış sync job. */
    @Autowired(required = false)
    private AcigaSatisSyncJob acigaSatisSyncJob;

    /** VBTS tedbir sync job. */
    @Autowired(required = false)
    private VbtsTedbirSyncJob vbtsTedbirSyncJob;

    /** Hedef fiyat sync job. */
    @Autowired(required = false)
    private HedefFiyatSyncJob hedefFiyatSyncJob;

    /** Guidance sync job. */
    @Autowired(required = false)
    private GuidanceSyncJob guidanceSyncJob;

    /** Halka arz sync job. */
    @Autowired(required = false)
    private HalkaArzSyncJob halkaArzSyncJob;

    /** Finansal oran sync job. */
    @Autowired(required = false)
    private FinansalOranSyncJob finansalOranSyncJob;

    /** Fon portföy sync job. */
    @Autowired(required = false)
    private FonPortfoySyncJob fonPortfoySyncJob;

    /** Finansal tablo sync job. */
    @Autowired(required = false)
    private FinansalTabloSyncJob finansalTabloSyncJob;

    /** State → code_verifier eşleştirmesi (PKCE akışı için). */
    private final ConcurrentHashMap<String, String> pkceStore = new ConcurrentHashMap<>();

    /** Güvenli rastgele sayı üreteci. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Singleton HTTP istemcisi (thread-safe, bağlantı havuzu paylaşımı). */
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /**
     * OAuth 2.0 PKCE akışını başlatır ve Fintables authorize URL'ye redirect eder.
     *
     * <p>PKCE code_verifier (43-128 karakter) ve code_challenge (Base64URL SHA-256) üretir,
     * state parametresi ile birlikte saklar ve kullanıcıyı Fintables'e yönlendirir.</p>
     *
     * @return 302 redirect response (Fintables authorize URL)
     */
    @GetMapping("/auth")
    public ResponseEntity<Void> authorize() {
        try {
            // Memory leak koruması: auth nadir yapılır, 50'den fazla birikirse temizle
            if (pkceStore.size() > 50) {
                pkceStore.clear();
            }

            String codeVerifier = generateCodeVerifier();
            String codeChallenge = generateCodeChallenge(codeVerifier);
            String state = generateState();

            // State -> code_verifier eşleştirmesini sakla
            pkceStore.put(state, codeVerifier);

            String authorizeUrl = config.getAuthorizationEndpoint()
                    + "?response_type=code"
                    + "&client_id=" + urlEncode(config.getClientId())
                    + "&redirect_uri=" + urlEncode(config.getCallbackUrl())
                    + "&code_challenge=" + urlEncode(codeChallenge)
                    + "&code_challenge_method=S256"
                    + "&state=" + urlEncode(state);

            log.info("Fintables MCP OAuth başlatılıyor, state: {}", state);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, authorizeUrl)
                    .build();
        } catch (Exception e) {
            log.error("Fintables MCP OAuth başlatma hatası", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * OAuth 2.0 callback endpoint'i. Authorization code ile token exchange yapar.
     *
     * <p>Fintables'ten gelen code ve state parametrelerini alır,
     * PKCE code_verifier ile token endpoint'e POST yaparak access_token alır.</p>
     *
     * @param code  authorization code
     * @param state CSRF koruması için state parametresi
     * @return başarılıysa token bilgisi, başarısızsa hata mesajı
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {

        // State doğrulaması
        String codeVerifier = pkceStore.remove(state);
        if (codeVerifier == null) {
            log.warn("Fintables MCP callback: geçersiz state parametresi: {}", state);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Geçersiz state parametresi"));
        }

        try {
            // Token exchange
            String requestBody = "grant_type=authorization_code"
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(config.getCallbackUrl())
                    + "&client_id=" + urlEncode(config.getClientId())
                    + "&code_verifier=" + urlEncode(codeVerifier);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getTokenEndpoint()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Fintables MCP token exchange hatası: status={}, body={}",
                        response.statusCode(), response.body());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Token exchange başarısız: " + response.statusCode()));
            }

            JsonNode tokenResponse = objectMapper.readTree(response.body());

            JsonNode accessTokenNode = tokenResponse.get("access_token");
            if (accessTokenNode == null || accessTokenNode.isNull()) {
                log.error("Fintables MCP token response 'access_token' içermiyor: {}", response.body());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Token response geçersiz: access_token yok"));
            }
            String accessToken = accessTokenNode.asText();
            long expiresIn = tokenResponse.has("expires_in")
                    ? tokenResponse.get("expires_in").asLong()
                    : 3600; // varsayılan 1 saat

            tokenStore.storeToken(accessToken, expiresIn);

            log.info("Fintables MCP token başarıyla alındı, geçerlilik: {} saniye", expiresIn);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Token başarıyla alındı",
                    "expires_in", expiresIn
            ));
        } catch (Exception e) {
            log.error("Fintables MCP token exchange hatası", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Token exchange hatası: " + e.getMessage()));
        }
    }

    /**
     * Mevcut token durumunu döndürür.
     *
     * @return token durumu (valid/expired/none)
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (tokenStore.isTokenValid()) {
            return ResponseEntity.ok(Map.of("status", "valid"));
        } else if (tokenStore.getAccessToken() != null) {
            return ResponseEntity.ok(Map.of("status", "expired"));
        } else {
            return ResponseEntity.ok(Map.of("status", "none"));
        }
    }

    /**
     * MCP sembol arama testi.
     *
     * @param q aranacak sembol (ör: THYAO)
     * @return arama sonuçları
     */
    @GetMapping("/test/sembol")
    public ResponseEntity<?> testSembol(@RequestParam("q") String q) {
        if (profileUtils.isProdProfile()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Test endpoint'leri production'da kapalı"));
        }
        try {
            JsonNode result = mcpClient.sembolArama(q);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * MCP SQL sorgusu testi.
     *
     * @param sql çalıştırılacak SQL
     * @return sorgu sonuçları
     */
    @GetMapping("/test/sql")
    public ResponseEntity<?> testSql(@RequestParam("sql") String sql) {
        if (profileUtils.isProdProfile()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Test endpoint'leri production'da kapalı"));
        }
        try {
            JsonNode result = mcpClient.veriSorgula(sql, "Test sorgusu");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ======================== Manuel Sync Tetikleme Endpoint'leri ========================

    /**
     * Tüm Fintables sync job'ları manuel olarak tetikler.
     *
     * <p>Her job'un {@code forceSync()} metodunu sırayla çağırır.
     * Guard clause'ları ve idempotent kontrolleri atlanır.</p>
     *
     * @return her job için sonuç durumu (OK veya ERROR)
     */
    @GetMapping("/trigger/all")
    public ResponseEntity<?> triggerAllSync() {
        if (!tokenStore.isTokenValid()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Fintables MCP token geçersiz"));
        }

        Map<String, String> results = new LinkedHashMap<>();

        triggerJob("acigaSatis", acigaSatisSyncJob, results);
        triggerJob("vbtsTedbir", vbtsTedbirSyncJob, results);
        triggerJob("hedefFiyat", hedefFiyatSyncJob, results);
        triggerJob("guidance", guidanceSyncJob, results);
        triggerJob("halkaArz", halkaArzSyncJob, results);
        triggerJob("finansalOran", finansalOranSyncJob, results);
        triggerJob("fonPortfoy", fonPortfoySyncJob, results);
        triggerJob("finansalTablo", finansalTabloSyncJob, results);

        return ResponseEntity.ok(results);
    }

    /**
     * Açığa satış sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/aciga-satis")
    public ResponseEntity<?> triggerAcigaSatis() {
        return triggerSingleJob("acigaSatis", acigaSatisSyncJob);
    }

    /**
     * VBTS tedbir sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/vbts")
    public ResponseEntity<?> triggerVbtsTedbir() {
        return triggerSingleJob("vbtsTedbir", vbtsTedbirSyncJob);
    }

    /**
     * Hedef fiyat sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/hedef-fiyat")
    public ResponseEntity<?> triggerHedefFiyat() {
        return triggerSingleJob("hedefFiyat", hedefFiyatSyncJob);
    }

    /**
     * Guidance sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/guidance")
    public ResponseEntity<?> triggerGuidance() {
        return triggerSingleJob("guidance", guidanceSyncJob);
    }

    /**
     * Halka arz sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/halka-arz")
    public ResponseEntity<?> triggerHalkaArz() {
        return triggerSingleJob("halkaArz", halkaArzSyncJob);
    }

    /**
     * Finansal oran sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/finansal-oran")
    public ResponseEntity<?> triggerFinansalOran() {
        return triggerSingleJob("finansalOran", finansalOranSyncJob);
    }

    /**
     * Fon portföy sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/fon-portfoy")
    public ResponseEntity<?> triggerFonPortfoy() {
        return triggerSingleJob("fonPortfoy", fonPortfoySyncJob);
    }

    /**
     * Finansal tablo sync job'ını manuel tetikler.
     *
     * @return sonuç durumu
     */
    @GetMapping("/trigger/finansal-tablo")
    public ResponseEntity<?> triggerFinansalTablo() {
        return triggerSingleJob("finansalTablo", finansalTabloSyncJob);
    }

    /**
     * Tek bir sync job'ı tetikler ve sonuç döndürür.
     *
     * @param name job adı
     * @param job  çalıştırılacak runnable (forceSync çağrısı)
     * @return başarı/hata response
     */
    private ResponseEntity<?> triggerSingleJob(String name, Object job) {
        if (!tokenStore.isTokenValid()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Fintables MCP token geçersiz"));
        }
        if (job == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", name + " job bean mevcut değil"));
        }

        Map<String, String> results = new LinkedHashMap<>();
        triggerJob(name, job, results);
        return ResponseEntity.ok(results);
    }

    /**
     * Bir sync job'ı çalıştırır ve sonucunu results map'e yazar.
     *
     * @param name    job adı
     * @param job     job nesnesi (forceSync metodu çağrılır)
     * @param results sonuç map'i
     */
    private void triggerJob(String name, Object job, Map<String, String> results) {
        if (job == null) {
            results.put(name, "SKIP: bean mevcut değil");
            return;
        }
        try {
            if (job instanceof AcigaSatisSyncJob j) { j.forceSync(); }
            else if (job instanceof VbtsTedbirSyncJob j) { j.forceSync(); }
            else if (job instanceof HedefFiyatSyncJob j) { j.forceSync(); }
            else if (job instanceof GuidanceSyncJob j) { j.forceSync(); }
            else if (job instanceof HalkaArzSyncJob j) { j.forceSync(); }
            else if (job instanceof FinansalOranSyncJob j) { j.forceSync(); }
            else if (job instanceof FonPortfoySyncJob j) { j.forceSync(); }
            else if (job instanceof FinansalTabloSyncJob j) { j.forceSync(); }
            results.put(name, "OK");
        } catch (Exception e) {
            results.put(name, "ERROR: " + e.getMessage());
        }
    }

    // ======================== PKCE Helper Metodları ========================

    /**
     * PKCE code_verifier üretir (43-128 karakter, URL-safe).
     *
     * @return rastgele code_verifier string'i
     */
    private String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * PKCE code_challenge üretir (Base64URL SHA-256).
     *
     * @param codeVerifier code_verifier string'i
     * @return Base64URL encoded SHA-256 hash
     * @throws Exception SHA-256 hesaplama hatası
     */
    private String generateCodeChallenge(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    /**
     * Rastgele state parametresi üretir (CSRF koruması).
     *
     * @return rastgele state string'i
     */
    private String generateState() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * URL encoding yardımcı metodu.
     *
     * @param value encode edilecek string
     * @return URL encoded string
     */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
