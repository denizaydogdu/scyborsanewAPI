package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.scyborsa.api.dto.market.DashboardSentimentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Piyasa sentiment (duyarlilik) verisini hesaplayan servis.
 *
 * <p>Velzon API uzerinden BIST hisselerinin performans verilerini ceker
 * ve kisa/orta/uzun vadeli yukselis oranlarini hesaplar.</p>
 *
 * <p>Veri kaynagi: {@code /api/pinescreener/velzon_performance} endpoint'i.
 * Her hisse icin {@code plot17} (kisa vadeli), {@code plot18} (orta vadeli),
 * {@code plot19} (uzun vadeli) alanlari 1 ise yukseliste kabul edilir.</p>
 *
 * @see VelzonApiClient
 * @see DashboardSentimentDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentService {

    /** Velzon API istemcisi. */
    private final VelzonApiClient velzonApiClient;

    /**
     * Piyasa sentiment verisini hesaplar ve dondurur.
     *
     * <p>Velzon API'den performans verisini ceker, {@code data} dizisindeki
     * her eleman icin {@code plot17}, {@code plot18}, {@code plot19} degerlerini
     * kontrol eder. Degeri 1 olan hisseler yukseliste kabul edilir ve
     * toplam hisse sayisina oranlanarak yuzde hesaplanir.</p>
     *
     * <p>Hata durumunda log yazilir ve tum degerleri 0.0 olan DTO dondurulur.</p>
     *
     * @return piyasa sentiment verisini iceren {@link DashboardSentimentDto}
     */
    public DashboardSentimentDto getSentimentData() {
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/pinescreener/velzon_performance",
                    new TypeReference<Map<String, Object>>() {}
            );

            Object rawData = response.get("data");
            if (!(rawData instanceof List)) {
                log.warn("Sentiment verisi beklenmeyen formatta: {}",
                        rawData != null ? rawData.getClass().getSimpleName() : "null");
                return buildEmptyDto();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;

            if (data.isEmpty()) {
                log.warn("Sentiment verisi bos dondu");
                return buildEmptyDto();
            }

            int total = data.size();
            int kisaCount = 0;
            int ortaCount = 0;
            int uzunCount = 0;

            for (Map<String, Object> item : data) {
                if (isPlotPositive(item, "plot17")) kisaCount++;
                if (isPlotPositive(item, "plot18")) ortaCount++;
                if (isPlotPositive(item, "plot19")) uzunCount++;
            }

            double kisaVadeli = Math.round(kisaCount * 1000.0 / total) / 10.0;
            double ortaVadeli = Math.round(ortaCount * 1000.0 / total) / 10.0;
            double uzunVadeli = Math.round(uzunCount * 1000.0 / total) / 10.0;

            return DashboardSentimentDto.builder()
                    .kisaVadeli(kisaVadeli)
                    .ortaVadeli(ortaVadeli)
                    .uzunVadeli(uzunVadeli)
                    .toplamHisse(total)
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();

        } catch (Exception e) {
            log.error("Sentiment verisi alinamadi: {}", e.getMessage(), e);
            return buildEmptyDto();
        }
    }

    /**
     * Belirtilen plot alaninin degerinin 1 (pozitif/yukselis) olup olmadigini kontrol eder.
     *
     * <p>Alan degeri {@link Number} tipinde ise {@code intValue() == 1} kontrolu yapilir.</p>
     *
     * @param item     veri satiri (hisse verisi map'i)
     * @param plotKey  kontrol edilecek alan adi (ör. "plot17")
     * @return alan degeri 1 ise {@code true}, aksi halde {@code false}
     */
    private boolean isPlotPositive(Map<String, Object> item, String plotKey) {
        Object value = item.get(plotKey);
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        return false;
    }

    /**
     * Tum degerleri 0.0 olan bos bir sentiment DTO'su olusturur.
     *
     * <p>Hata durumlarinda veya veri alinamadiginda kullanilir (graceful degradation).</p>
     *
     * @return tum alanlari sifir olan {@link DashboardSentimentDto}
     */
    private DashboardSentimentDto buildEmptyDto() {
        return DashboardSentimentDto.builder()
                .kisaVadeli(0.0)
                .ortaVadeli(0.0)
                .uzunVadeli(0.0)
                .toplamHisse(0)
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }
}
