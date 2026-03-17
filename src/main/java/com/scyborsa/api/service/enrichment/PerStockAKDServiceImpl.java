package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.AkdBrokerDto;
import com.scyborsa.api.dto.enrichment.AkdResponseDto;
import com.scyborsa.api.dto.enrichment.StockBrokerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hisse bazlı AKD (Aracı Kurum Dağılımı) servis implementasyonu.
 *
 * <p>{@link AkdService} üzerinden alınan zenginleştirilmiş AKD verisini
 * Telegram mesajlarında kullanılmak üzere {@link StockBrokerInfo} listesine dönüştürür.</p>
 *
 * <p>İlk 3 alıcı kurum (🟢) ve ilk 2 satıcı kurum (🔴) bilgisi döner.
 * Tüm hatalar yakalanır, boş liste döner — graceful degradation.</p>
 *
 * @see AkdService
 * @see StockBrokerInfo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerStockAKDServiceImpl implements PerStockAKDService {

    /** AKD iş mantığı servisi. */
    private final AkdService akdService;

    /**
     * Hissenin kurum bazlı alım/satım dağılımını getirir.
     *
     * <p>AkdService'ten alınan veriden ilk 3 alıcı ve ilk 2 satıcı kurumu
     * {@link StockBrokerInfo} formatına dönüştürerek döner.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return kurum dağılım listesi (alıcı + satıcı); hata durumunda boş liste
     */
    @Override
    public List<StockBrokerInfo> getStockBrokerDistribution(String stockName) {
        try {
            AkdResponseDto akdData = akdService.getAkdDistribution(stockName);
            if (akdData == null) {
                log.debug("[PerStockAKD] AKD verisi bulunamadı: stockName={}", stockName);
                return Collections.emptyList();
            }

            List<StockBrokerInfo> result = new ArrayList<>();

            // İlk 3 alıcı kurum
            if (akdData.getAlicilar() != null) {
                akdData.getAlicilar().stream()
                        .limit(3)
                        .map(broker -> toBrokerInfo(broker, true))
                        .forEach(result::add);
            }

            // İlk 2 satıcı kurum
            if (akdData.getSaticilar() != null) {
                akdData.getSaticilar().stream()
                        .limit(2)
                        .map(broker -> toBrokerInfo(broker, false))
                        .forEach(result::add);
            }

            log.debug("[PerStockAKD] {} kurum dağılımı döndü: stockName={}", result.size(), stockName);
            return result;
        } catch (Exception e) {
            log.error("[PerStockAKD] Kurum dağılımı alınırken hata: stockName={}", stockName, e);
            return Collections.emptyList();
        }
    }

    /**
     * AKD broker DTO'sunu Telegram formatına uygun {@link StockBrokerInfo} nesnesine dönüştürür.
     *
     * @param broker  AKD broker verisi
     * @param isAlici alıcı ise {@code true}, satıcı ise {@code false}
     * @return Telegram formatına uygun kurum bilgisi
     */
    private StockBrokerInfo toBrokerInfo(AkdBrokerDto broker, boolean isAlici) {
        return StockBrokerInfo.builder()
                .brokerName(broker.getShortTitle())
                .emoji(isAlici ? "🟢" : "🔴")
                .formattedVolume(formatMaliyet(broker.getMaliyet(), isAlici))
                .build();
    }

    /**
     * TL maliyet değerini Türkçe okunabilir formata dönüştürür.
     *
     * <p>Alıcılar için "+" prefix'i, satıcılar için "-" prefix'i eklenir.
     * Milyar, Milyon, Bin ölçekleri kullanılır (İngilizce kısaltma kullanılmaz).</p>
     *
     * @param maliyet TL cinsinden maliyet değeri
     * @param isAlici alıcı ise {@code true} (+ prefix), satıcı ise {@code false} (- prefix)
     * @return formatlanmış string (ör: "+2.15 Milyar")
     */
    private String formatMaliyet(double maliyet, boolean isAlici) {
        String prefix = isAlici ? "+" : "-";
        double abs = Math.abs(maliyet);
        if (abs >= 1_000_000_000) {
            return String.format("%s%.2f Milyar", prefix, abs / 1_000_000_000.0);
        } else if (abs >= 1_000_000) {
            return String.format("%s%.2f Milyon", prefix, abs / 1_000_000.0);
        } else if (abs >= 1_000) {
            return String.format("%s%.2f Bin", prefix, abs / 1_000.0);
        }
        return String.format("%s%.2f", prefix, abs);
    }
}
