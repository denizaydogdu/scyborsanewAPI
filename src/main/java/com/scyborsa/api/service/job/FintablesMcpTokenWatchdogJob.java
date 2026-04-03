package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fintables MCP token geçerlilik kontrol job'u.
 *
 * <p>Her gün 10:00'da (Europe/Istanbul) çalışır. Token süresi dolmuşsa veya
 * 48 saat içinde dolacaksa Telegram üzerinden uyarı gönderir.</p>
 *
 * <p>Sadece production profilinde ve Telegram aktifken çalışır.</p>
 *
 * @see FintablesMcpTokenStore
 * @see TelegramClient
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FintablesMcpTokenWatchdogJob {

    /** Expire uyarısı için eşik (saat). */
    private static final long EXPIRY_WARNING_HOURS = 48;

    private final FintablesMcpTokenStore tokenStore;
    private final TelegramClient telegramClient;
    private final TelegramConfig telegramConfig;
    private final ProfileUtils profileUtils;

    /**
     * Her gün 10:00'da Fintables MCP token durumunu kontrol eder.
     * Token expire olduysa veya 48 saat içinde olacaksa Telegram uyarısı gönderir.
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Europe/Istanbul")
    public void checkTokenExpiry() {
        if (!profileUtils.isProdProfile() || !telegramConfig.isEnabled()) {
            return;
        }

        if (!tokenStore.isTokenValid()) {
            String message = "<b>\u26a0\ufe0f Fintables MCP Token Expired!</b>\n\n"
                    + "Token süresi dolmuş veya mevcut değil.\n"
                    + "<code>/api/v1/fintables/auth</code> ile yenileyin.";
            sendAlert(message);
            log.warn("[MCP-WATCHDOG] Fintables MCP token expired! Telegram uyarısı gönderildi.");
            return;
        }

        long hoursLeft = tokenStore.getHoursUntilExpiry();
        if (hoursLeft < EXPIRY_WARNING_HOURS) {
            long days = hoursLeft / 24;
            long remainingHours = hoursLeft % 24;
            String timeStr = days > 0
                    ? days + " gün " + remainingHours + " saat"
                    : hoursLeft + " saat";
            String message = "<b>\u23f0 Fintables MCP Token Uyarısı</b>\n\n"
                    + "Token <b>" + timeStr + "</b> içinde expire olacak!\n"
                    + "<code>/api/v1/fintables/auth</code> ile yenileyin.";
            sendAlert(message);
            log.warn("[MCP-WATCHDOG] Fintables MCP token {} saat içinde expire olacak! "
                    + "Telegram uyarısı gönderildi.", hoursLeft);
            return;
        }

        log.debug("[MCP-WATCHDOG] Fintables MCP token geçerli, kalan: {} saat", hoursLeft);
    }

    /**
     * Telegram üzerinden uyarı mesajı gönderir.
     *
     * @param html gönderilecek HTML mesaj
     */
    private void sendAlert(String html) {
        try {
            String chatId = telegramConfig.getBot().getChatId();
            int topicId = telegramConfig.getBot().getTopicId();
            telegramClient.sendHtmlMessage(html, chatId, topicId);
        } catch (Exception e) {
            log.error("[MCP-WATCHDOG] Telegram uyarısı gönderilemedi: {}", e.getMessage());
        }
    }
}
