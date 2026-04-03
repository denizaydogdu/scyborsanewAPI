package com.scyborsa.api.controller;

import com.scyborsa.api.service.telegram.TelegramBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Telegram bot temel analiz REST controller'ı.
 *
 * <p>Telegram bot sorguları için şirket raporu özeti döndüren
 * endpoint sağlar. Şimdilik doğrudan webhook entegrasyonu yoktur;
 * HTTP POST üzerinden sorgu kabul eder.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/telegram-bot}</p>
 *
 * @see TelegramBotService
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/telegram-bot")
@RequiredArgsConstructor
public class TelegramBotController {

    private final TelegramBotService telegramBotService;

    /**
     * Telegram bot sorgusunu işler ve HTML formatında yanıt döndürür.
     *
     * @param body sorgu body'si, {@code "query"} anahtarı ile hisse kodu/sorgu (ör: {"query":"THYAO"})
     * @return {@link ResponseEntity} içinde HTML formatında yanıt string'i
     */
    @PostMapping("/query")
    public ResponseEntity<String> handleQuery(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        log.debug("[TELEGRAM-BOT] Sorgu isteği: query={}", query);
        String result = telegramBotService.handleBotQuery(query);
        return ResponseEntity.ok(result);
    }
}
