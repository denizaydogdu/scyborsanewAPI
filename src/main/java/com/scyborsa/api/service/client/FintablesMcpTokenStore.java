package com.scyborsa.api.service.client;

import com.scyborsa.api.model.FintablesMcpToken;
import com.scyborsa.api.repository.FintablesMcpTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Fintables MCP OAuth 2.0 access token'ini bellekte ve veritabanında saklayan bileşen.
 *
 * <p>Thread-safe erişim için volatile {@link TokenState} record kullanır (atomic snapshot).
 * Startup'ta DB'den token yüklenir, storeToken() hem volatile alanı hem DB'yi günceller.
 * Server restart'ta token kaybolmaz.</p>
 *
 * <p>Refresh token desteklenmediğinden (Fintables MCP sadece authorization_code grant type destekler),
 * token süresi dolduğunda yeniden OAuth akışı başlatılmalıdır.</p>
 *
 * @see com.scyborsa.api.controller.FintablesMcpAuthController
 * @see FintablesMcpClient
 * @see com.scyborsa.api.model.FintablesMcpToken
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FintablesMcpTokenStore {

    /** DB'deki token kaydının varsayılan anahtarı. */
    private static final String TOKEN_KEY = "MCP_ACCESS_TOKEN";

    /**
     * Token durumunu atomik olarak saklayan record.
     * Volatile reference ile thread-safe okuma/yazma sağlanır.
     *
     * @param accessToken OAuth 2.0 access token
     * @param expiresAt   token geçerlilik bitiş zamanı
     */
    private record TokenState(String accessToken, Instant expiresAt) {}

    /** Mevcut token durumu (atomic snapshot). */
    private volatile TokenState tokenState = new TokenState(null, null);

    private final FintablesMcpTokenRepository tokenRepository;

    /**
     * Uygulama hazır olduğunda veritabanından token yükler.
     * Token bulunamazsa veya süresi dolmuşsa uyarı loglar.
     *
     * <p>{@code @PostConstruct} yerine {@code ApplicationReadyEvent} kullanılır,
     * böylece DB bağlantısı ve tüm bean'ler hazır olduktan sonra çalışır (ERR-601).</p>
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    void loadFromDb() {
        tokenRepository.findByTokenKey(TOKEN_KEY).ifPresentOrElse(
                entity -> {
                    this.tokenState = new TokenState(entity.getAccessToken(), entity.getExpiresAt());
                    if (isTokenValid()) {
                        log.info("[MCP-TOKEN] DB'den token yüklendi, kalan süre: {} saat",
                                getHoursUntilExpiry());
                    } else {
                        log.warn("[MCP-TOKEN] DB'deki token süresi dolmuş! "
                                + "OAuth akışı ile yenilenmelidir.");
                        this.tokenState = new TokenState(null, null);
                    }
                },
                () -> log.warn("[MCP-TOKEN] DB'de token bulunamadı. "
                        + "OAuth akışı ile token alınmalıdır.")
        );
    }

    /**
     * Yeni bir access token saklar (volatile + DB persist).
     *
     * @param accessToken      OAuth 2.0 access token
     * @param expiresInSeconds token'in geçerlilik süresi (saniye)
     */
    @Transactional
    public void storeToken(String accessToken, long expiresInSeconds) {
        // 30 saniye erken expire et (clock skew koruması)
        Instant newExpiresAt = Instant.now().plusSeconds(expiresInSeconds - 30);

        // Önce DB persist, başarılı olursa memory güncelle
        persistToDb(accessToken, newExpiresAt);
        this.tokenState = new TokenState(accessToken, newExpiresAt);
        log.info("[MCP-TOKEN] Token saklandı (volatile + DB), geçerlilik: {} saniye", expiresInSeconds);
    }

    /**
     * Mevcut access token'i döndürür.
     *
     * @return access token veya null (henüz alınmamışsa)
     */
    public String getAccessToken() {
        return tokenState.accessToken();
    }

    /**
     * Token'in geçerli olup olmadığını kontrol eder.
     *
     * @return token mevcutsa ve süresi dolmamışsa true
     */
    public boolean isTokenValid() {
        TokenState ts = tokenState;
        return ts.accessToken() != null && ts.expiresAt() != null && Instant.now().isBefore(ts.expiresAt());
    }

    /**
     * Token'in dolmasına kalan saat sayısını hesaplar.
     *
     * @return kalan saat sayısı. Token geçersizse veya yoksa 0
     */
    public long getHoursUntilExpiry() {
        TokenState ts = tokenState;
        if (ts.expiresAt() == null) {
            return 0;
        }
        long hours = Duration.between(Instant.now(), ts.expiresAt()).toHours();
        return Math.max(hours, 0);
    }

    /**
     * Token'in dolmasına kalan zamanı döndürür.
     *
     * @return kalan süre veya null (token yoksa)
     */
    public Instant getExpiresAt() {
        return tokenState.expiresAt();
    }

    /**
     * Saklanan token'i temizler (volatile + DB).
     */
    @Transactional
    public void clearToken() {
        // Önce DB sil, başarılı olursa memory temizle
        deleteFromDb();
        this.tokenState = new TokenState(null, null);
        log.info("[MCP-TOKEN] Token temizlendi (volatile + DB)");
    }

    /**
     * Token'i veritabanına kaydeder. Mevcut kayıt varsa günceller, yoksa oluşturur.
     *
     * @param token     access token değeri
     * @param expiresAt token bitiş zamanı
     */
    synchronized void persistToDb(String token, Instant expiresAt) {
        FintablesMcpToken entity = tokenRepository.findByTokenKey(TOKEN_KEY)
                .orElseGet(() -> FintablesMcpToken.builder()
                        .tokenKey(TOKEN_KEY)
                        .build());
        entity.setAccessToken(token);
        entity.setExpiresAt(expiresAt);
        tokenRepository.save(entity);
        log.debug("[MCP-TOKEN] Token DB'ye kaydedildi");
    }

    /**
     * Token kaydını veritabanından siler.
     */
    synchronized void deleteFromDb() {
        tokenRepository.deleteByTokenKey(TOKEN_KEY);
        log.debug("[MCP-TOKEN] Token DB'den silindi");
    }
}
