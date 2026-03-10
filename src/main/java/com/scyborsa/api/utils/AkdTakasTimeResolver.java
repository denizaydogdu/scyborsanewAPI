package com.scyborsa.api.utils;

import com.scyborsa.api.enums.SessionHolidays;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * AKD ve Takas verileri için zaman bazlı okuma stratejisi çözümleyicisi.
 *
 * <p>Günün saatine ve işlem takvimindeki durumuna göre verinin nereden
 * okunacağını (DB vs API) belirler. Hem {@code AkdService} hem de
 * {@code TakasService} tarafından ortak kullanılır.</p>
 *
 * <pre>
 *   00:00 – 09:50  → DB_PREVIOUS_DAY  (önceki iş günü)
 *   09:50 – close   → LIVE_API         (canlı API)
 *   close – 19:05   → DB_FIRST_THEN_API (DB varsa DB, yoksa API + kaydet)
 *   19:05 – 23:59   → DB_ONLY          (sadece DB)
 *   Hafta sonu/tatil → DB_PREVIOUS_DAY
 * </pre>
 */
@Component
public class AkdTakasTimeResolver {

    /**
     * Veri okuma stratejisi.
     */
    public enum ReadStrategy {
        /** Önceki iş günü verisini DB'den oku. */
        DB_PREVIOUS_DAY,
        /** Canlı API'den çek. */
        LIVE_API,
        /** DB'de varsa DB'den, yoksa API'den çek + DB'ye kaydet. */
        DB_FIRST_THEN_API,
        /** Sadece DB'den oku (job zaten tamamlamış olmalı). */
        DB_ONLY
    }

    /**
     * Verilen tarih ve saat için okuma stratejisini belirler.
     *
     * @param today bugünün tarihi
     * @param now   şu anki saat
     * @return uygulanacak okuma stratejisi
     */
    public ReadStrategy resolve(LocalDate today, LocalTime now) {
        // Hafta sonu veya tatil → önceki iş günü
        if (SessionHolidays.isNonTradingDay(today)) {
            return ReadStrategy.DB_PREVIOUS_DAY;
        }

        // Yarım gün kontrolü — arefe ise kapanış saati daha erken
        LocalTime closingTime = SessionHolidays.getHalfDayClosingTime(today);
        LocalTime effectiveClose = (closingTime != null) ? closingTime : LocalTime.of(18, 15);

        // 00:00 – 09:50 → önceki iş günü verisi
        if (now.isBefore(LocalTime.of(9, 50))) {
            return ReadStrategy.DB_PREVIOUS_DAY;
        }

        // 09:50 – effectiveClose → canlı API
        if (now.isBefore(effectiveClose)) {
            return ReadStrategy.LIVE_API;
        }

        // effectiveClose – 19:05 → DB first, yoksa API + kaydet
        if (now.isBefore(LocalTime.of(19, 5))) {
            return ReadStrategy.DB_FIRST_THEN_API;
        }

        // 19:05 – 23:59 → sadece DB
        return ReadStrategy.DB_ONLY;
    }
}
