package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ML feature engineering servisi.
 *
 * <p>Bir hissenin temel analiz verilerinden makine öğrenmesi modeli
 * için kullanılabilecek sayısal feature'lar çıkarır. Her feature
 * bağımsız try-catch ile sarılıdır; hesaplanamazsa atlanır
 * (graceful degradation).</p>
 *
 * <h3>Feature Listesi:</h3>
 * <ul>
 *   <li>{@code fk} — Fiyat/Kazanç oranı</li>
 *   <li>{@code pdDd} — Piyasa Değeri / Defter Değeri</li>
 *   <li>{@code roe} — Özsermaye Kârlılığı (%)</li>
 *   <li>{@code roa} — Aktif Kârlılığı (%)</li>
 *   <li>{@code netBorcFavok} — Net Borç / FAVÖK</li>
 *   <li>{@code cariOran} — Cari Oran</li>
 *   <li>{@code brutMarj} — Brüt Kâr Marjı (%)</li>
 *   <li>{@code netMarj} — Net Kâr Marjı (%)</li>
 *   <li>{@code acigaSatisOrani} — Açığa Satış Oranı (%)</li>
 *   <li>{@code analistSayisi} — Analist sayısı (hedef fiyat veren)</li>
 *   <li>{@code pozitifSinyalOrani} — Pozitif sinyal oranı (0.0-1.0)</li>
 * </ul>
 *
 * @see FinansalOranService
 * @see FinansalTabloService
 * @see AcigaSatisService
 * @see HedefFiyatService
 */
@Slf4j
@Service
public class MlFeatureService {

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Finansal tablo servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /** Açığa satış servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private AcigaSatisService acigaSatisService;

    /** Hedef fiyat servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private HedefFiyatService hedefFiyatService;

    /**
     * Belirtilen hisse için ML feature'larını çıkarır.
     *
     * <p>Her feature bağımsız hesaplanır; biri başarısız olursa
     * sonuç map'ine eklenmez. Dönen map'te yalnızca başarıyla
     * hesaplanan feature'lar bulunur.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return feature adı → değer map'i (hesaplanamayanlar dahil edilmez)
     */
    public Map<String, Double> extractFeatures(String stockCode) {
        log.debug("[ML-FEATURE] Feature çıkarımı başlatıldı: stockCode={}", stockCode);

        Map<String, Double> features = new LinkedHashMap<>();

        // F/K, PD/DD, ROE — FinansalOranService
        extractFinansalOranFeatures(features, stockCode);

        // ROA, Net Borç/FAVÖK, Cari Oran, Brüt Marj, Net Marj — FinansalTabloService
        extractFinansalTabloFeatures(features, stockCode);

        // Açığa satış oranı — AcigaSatisService
        extractAcigaSatisFeature(features, stockCode);

        // Analist sayısı — HedefFiyatService
        extractAnalistFeature(features, stockCode);

        log.debug("[ML-FEATURE] Feature çıkarımı tamamlandı: stockCode={}, featureSayısı={}",
                stockCode, features.size());
        return features;
    }

    /**
     * Finansal oran feature'larını çıkarır (F/K, PD/DD, ROE).
     *
     * @param features feature map
     * @param stockCode hisse kodu
     */
    private void extractFinansalOranFeatures(Map<String, Double> features, String stockCode) {
        try {
            if (finansalOranService != null) {
                List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
                if (oranlar != null) {
                    for (FinansalOranDto oran : oranlar) {
                        if (oran.getOran() == null || oran.getDeger() == null) continue;
                        switch (oran.getOran()) {
                            case "F/K" -> features.put("fk", oran.getDeger());
                            case "PD/DD" -> features.put("pdDd", oran.getDeger());
                            case "ROE" -> features.put("roe", oran.getDeger());
                            case "ROA" -> features.put("roa", oran.getDeger());
                            case "Net Borç/FAVÖK" -> features.put("netBorcFavok", oran.getDeger());
                            case "Cari Oran" -> features.put("cariOran", oran.getDeger());
                            case "Brüt Kâr Marjı" -> features.put("brutMarj", oran.getDeger());
                            case "Net Kâr Marjı" -> features.put("netMarj", oran.getDeger());
                            default -> { /* diğer oranlar atlanır */ }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ML-FEATURE] Finansal oran feature alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
        }
    }

    /**
     * Finansal tablo feature'larını çıkarır (bilanço kalem değerleri).
     *
     * <p>FinansalTabloDto muhasebe kalemleri içerir (kalem/tryDonemsel).
     * Doğrudan oran feature'ı sağlamaz; oran feature'ları
     * {@link #extractFinansalOranFeatures} ile çıkarılır.</p>
     *
     * @param features feature map
     * @param stockCode hisse kodu
     */
    private void extractFinansalTabloFeatures(Map<String, Double> features, String stockCode) {
        // FinansalTabloDto muhasebe satırları içerir (kalem/tryDonemsel/usdDonemsel).
        // Oran bazlı feature'lar (ROA, Net Borç/FAVÖK vb.) FinansalOranService'ten alınır.
        // Bu metot gelecekte bilanço kalemlerinden türetilecek feature'lar için ayrılmıştır.
        log.trace("[ML-FEATURE] Finansal tablo feature çıkarımı (stub): stockCode={}", stockCode);
    }

    /**
     * Açığa satış oranı feature'ını çıkarır.
     *
     * @param features feature map
     * @param stockCode hisse kodu
     */
    private void extractAcigaSatisFeature(Map<String, Double> features, String stockCode) {
        try {
            if (acigaSatisService != null) {
                List<AcigaSatisDto> acigaSatislar = acigaSatisService.getHisseAcigaSatis(stockCode);
                if (acigaSatislar != null && !acigaSatislar.isEmpty()) {
                    AcigaSatisDto son = acigaSatislar.get(0);
                    // Açığa satış oranı: hacim / toplam işlem hacmi
                    if (son.getAcigaSatisHacmiTl() != null && son.getToplamIslemHacmiTl() != null
                            && son.getToplamIslemHacmiTl() > 0) {
                        double oran = (son.getAcigaSatisHacmiTl() / son.getToplamIslemHacmiTl()) * 100.0;
                        features.put("acigaSatisOrani", oran);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ML-FEATURE] Açığa satış feature alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
        }
    }

    /**
     * Analist sayısı feature'ını çıkarır.
     *
     * @param features feature map
     * @param stockCode hisse kodu
     */
    private void extractAnalistFeature(Map<String, Double> features, String stockCode) {
        try {
            if (hedefFiyatService != null) {
                List<HedefFiyatDto> hedefler = hedefFiyatService.getHisseHedefFiyatlar(stockCode);
                if (hedefler != null) {
                    features.put("analistSayisi", (double) hedefler.size());
                }
            }
        } catch (Exception e) {
            log.warn("[ML-FEATURE] Analist feature alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
        }
    }
}
