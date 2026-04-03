package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.*;
import com.scyborsa.api.service.client.FintablesMcpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Şirket raporu motoru servisi.
 *
 * <p>Fintables MCP ve zenginleştirme servislerinden toplanan verileri
 * markdown formatında bir şirket raporu olarak derler. Claude API
 * entegrasyonu şimdilik mevcut değildir; sadece veri toplama ve
 * template markdown üretimi yapılır.</p>
 *
 * <h3>Rapor Bölümleri:</h3>
 * <ol>
 *   <li>Şirket Özeti</li>
 *   <li>Finansal Oranlar (F/K, PD/DD, ROE, ROA)</li>
 *   <li>Bilanço Özet</li>
 *   <li>Analist Konsensüs ve Hedef Fiyat</li>
 *   <li>Guidance (Şirket Beklentileri)</li>
 *   <li>Temel Analiz Sinyalleri</li>
 *   <li>Değerlendirme Skorları (Altman, Piotroski, Graham)</li>
 * </ol>
 *
 * @see FinansalOranService
 * @see FinansalTabloService
 * @see HedefFiyatService
 * @see GuidanceService
 * @see TemelAnalizSkorService
 */
@Slf4j
@Service
public class SirketRaporService {

    /** Tarih formatlayıcı. */
    private static final DateTimeFormatter TARIH_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Finansal tablo servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /** Hedef fiyat servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private HedefFiyatService hedefFiyatService;

    /** Guidance servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private GuidanceService guidanceService;

    /** Fintables MCP istemcisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpClient fintablesMcpClient;

    /** Temel analiz skor servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private TemelAnalizSkorService temelAnalizSkorService;

    /**
     * Belirtilen hisse için markdown formatında şirket raporu üretir.
     *
     * <p>Her veri kaynağı bağımsız try-catch ile sarılıdır. Bir kaynak
     * başarısız olursa ilgili bölüm "Veri mevcut değil" olarak yazılır
     * (graceful degradation).</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return markdown formatında şirket raporu
     */
    public String generateRapor(String stockCode) {
        log.debug("[SIRKET-RAPOR] Rapor üretimi başlatıldı: stockCode={}", stockCode);

        StringBuilder rapor = new StringBuilder();
        rapor.append("# ").append(stockCode).append(" — Şirket Raporu\n\n");
        rapor.append("_Rapor tarihi: ").append(LocalDate.now().format(TARIH_FORMAT)).append("_\n\n");

        // Bölüm 1: Şirket Özeti
        rapor.append("## 1. Şirket Özeti\n\n");
        rapor.append("Hisse kodu: **").append(stockCode).append("**\n\n");

        // Bölüm 2: Finansal Oranlar
        rapor.append("## 2. Finansal Oranlar\n\n");
        appendFinansalOranlar(rapor, stockCode);

        // Bölüm 3: Bilanço Özet
        rapor.append("## 3. Bilanço Özet\n\n");
        appendBilancoOzet(rapor, stockCode);

        // Bölüm 4: Analist Konsensüs
        rapor.append("## 4. Analist Konsensüs ve Hedef Fiyat\n\n");
        appendAnalistKonsensus(rapor, stockCode);

        // Bölüm 5: Guidance
        rapor.append("## 5. Şirket Beklentileri (Guidance)\n\n");
        appendGuidance(rapor, stockCode);

        // Bölüm 6: Sinyaller
        rapor.append("## 6. Temel Analiz Sinyalleri\n\n");
        rapor.append("_Sinyal verisi ayrı endpoint üzerinden sunulmaktadır._\n\n");

        // Bölüm 7: Değerlendirme Skorları
        rapor.append("## 7. Değerlendirme Skorları\n\n");
        appendDegerlendirmeSkorlari(rapor, stockCode);

        log.debug("[SIRKET-RAPOR] Rapor üretildi: stockCode={}, uzunluk={}", stockCode, rapor.length());
        return rapor.toString();
    }

    /**
     * Finansal oranlar bölümünü rapora ekler.
     *
     * @param rapor rapor StringBuilder
     * @param stockCode hisse kodu
     */
    private void appendFinansalOranlar(StringBuilder rapor, String stockCode) {
        try {
            if (finansalOranService != null) {
                List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
                if (oranlar != null && !oranlar.isEmpty()) {
                    rapor.append("| Oran | Değer |\n");
                    rapor.append("|------|-------|\n");
                    for (FinansalOranDto oran : oranlar) {
                        rapor.append("| ").append(nullSafe(oran.getOran()));
                        rapor.append(" | ").append(formatDeger(oran.getDeger()));
                        rapor.append(" |\n");
                    }
                    rapor.append("\n");
                    return;
                }
            }
            rapor.append("_Veri mevcut değil._\n\n");
        } catch (Exception e) {
            log.warn("[SIRKET-RAPOR] Finansal oranlar alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
            rapor.append("_Veri alınamadı._\n\n");
        }
    }

    /**
     * Bilanço özet bölümünü rapora ekler.
     *
     * @param rapor rapor StringBuilder
     * @param stockCode hisse kodu
     */
    private void appendBilancoOzet(StringBuilder rapor, String stockCode) {
        try {
            if (finansalTabloService != null) {
                List<FinansalTabloDto> tablolar = finansalTabloService.getHisseBilanco(stockCode);
                if (tablolar != null && !tablolar.isEmpty()) {
                    rapor.append("Son ").append(tablolar.size()).append(" dönem verisi mevcut.\n\n");
                    return;
                }
            }
            rapor.append("_Veri mevcut değil._\n\n");
        } catch (Exception e) {
            log.warn("[SIRKET-RAPOR] Bilanço verisi alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
            rapor.append("_Veri alınamadı._\n\n");
        }
    }

    /**
     * Analist konsensüs bölümünü rapora ekler.
     *
     * @param rapor rapor StringBuilder
     * @param stockCode hisse kodu
     */
    private void appendAnalistKonsensus(StringBuilder rapor, String stockCode) {
        try {
            if (hedefFiyatService != null) {
                List<HedefFiyatDto> hedefler = hedefFiyatService.getHisseHedefFiyatlar(stockCode);
                if (hedefler != null && !hedefler.isEmpty()) {
                    rapor.append("| Kurum Kodu | Tavsiye | Hedef Fiyat |\n");
                    rapor.append("|------------|---------|-------------|\n");
                    for (HedefFiyatDto hedef : hedefler) {
                        rapor.append("| ").append(nullSafe(hedef.getAraciKurumKodu()));
                        rapor.append(" | ").append(nullSafe(hedef.getTavsiye()));
                        rapor.append(" | ").append(formatDeger(hedef.getHedefFiyat()));
                        rapor.append(" |\n");
                    }
                    rapor.append("\n");
                    return;
                }
            }
            rapor.append("_Veri mevcut değil._\n\n");
        } catch (Exception e) {
            log.warn("[SIRKET-RAPOR] Analist konsensüs alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
            rapor.append("_Veri alınamadı._\n\n");
        }
    }

    /**
     * Guidance bölümünü rapora ekler.
     *
     * @param rapor rapor StringBuilder
     * @param stockCode hisse kodu
     */
    private void appendGuidance(StringBuilder rapor, String stockCode) {
        try {
            if (guidanceService != null) {
                List<GuidanceDto> guidanceList = guidanceService.getHisseGuidance(stockCode);
                if (guidanceList != null && !guidanceList.isEmpty()) {
                    for (GuidanceDto g : guidanceList) {
                        rapor.append("- **").append(g.getYil()).append(":** ")
                                .append(nullSafe(g.getBeklentiler())).append("\n");
                    }
                    rapor.append("\n");
                    return;
                }
            }
            rapor.append("_Veri mevcut değil._\n\n");
        } catch (Exception e) {
            log.warn("[SIRKET-RAPOR] Guidance alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
            rapor.append("_Veri alınamadı._\n\n");
        }
    }

    /**
     * Değerlendirme skorları bölümünü rapora ekler.
     *
     * @param rapor rapor StringBuilder
     * @param stockCode hisse kodu
     */
    private void appendDegerlendirmeSkorlari(StringBuilder rapor, String stockCode) {
        try {
            if (temelAnalizSkorService != null) {
                TemelAnalizSkorDto skor = temelAnalizSkorService.calculateScores(stockCode);
                if (skor != null) {
                    rapor.append("| Skor | Değer | Bölge |\n");
                    rapor.append("|------|-------|-------|\n");
                    rapor.append("| Altman Z-Score | ").append(formatDeger(skor.getAltmanZScore()));
                    rapor.append(" | ").append(nullSafe(skor.getAltmanZone())).append(" |\n");
                    rapor.append("| Piotroski F-Score | ").append(skor.getPiotroskiFScore() != null ? skor.getPiotroskiFScore() : "-");
                    rapor.append(" | ").append(nullSafe(skor.getPiotroskiZone())).append(" |\n");
                    rapor.append("| Graham Sayısı | ").append(formatDeger(skor.getGrahamSayisi()));
                    rapor.append(" | ").append(formatDeger(skor.getGrahamMarji())).append("% marj |\n");
                    rapor.append("\n");
                    return;
                }
            }
            rapor.append("_Veri mevcut değil._\n\n");
        } catch (Exception e) {
            log.warn("[SIRKET-RAPOR] Değerlendirme skorları alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
            rapor.append("_Veri alınamadı._\n\n");
        }
    }

    /**
     * Double değeri formatlı string'e çevirir.
     *
     * @param deger sayısal değer
     * @return formatlı string veya "-" (null ise)
     */
    private String formatDeger(Double deger) {
        return deger != null ? String.format("%.2f", deger) : "-";
    }

    /**
     * Null-safe string dönüşümü.
     *
     * @param value string değer
     * @return değer veya "-" (null ise)
     */
    private String nullSafe(String value) {
        return value != null ? value : "-";
    }
}
