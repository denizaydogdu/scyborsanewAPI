package com.scyborsa.api.service;

import com.scyborsa.api.dto.tarama.TaramaDto;
import com.scyborsa.api.dto.tarama.TaramaOzetDto;
import com.scyborsa.api.dto.tarama.TaramalarResponseDto;
import com.scyborsa.api.model.screener.ScreenerResultModel;
import com.scyborsa.api.repository.ScreenerResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Taramalar sayfası iş mantığı servisi.
 *
 * <p>Telegram'a gönderilmiş tarama sonuçlarını tarih aralığı, tarama adı ve hisse
 * filtrelerine göre sorgular; gruplama, özet istatistik hesaplama ve DTO dönüşümü yapar.</p>
 *
 * @see com.scyborsa.api.controller.TaramalarController
 * @see ScreenerResultRepository
 */
@Service
@RequiredArgsConstructor
public class TaramalarService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ScreenerResultRepository screenerResultRepository;

    /**
     * Taramalar sayfası için sinyal verilerini getirir.
     *
     * <p>Telegram'a gönderilmiş sonuçları tarih aralığına göre çeker,
     * opsiyonel tarama adı ve hisse filtreleri uygular, gruplama yapar
     * ve özet istatistikleri hesaplar.</p>
     *
     * @param startDate başlangıç tarihi (dahil)
     * @param endDate bitiş tarihi (dahil)
     * @param screener tarama adı filtresi (contains, case-insensitive; null ise filtre yok)
     * @param stock hisse kodu filtresi (contains, case-insensitive; null ise filtre yok)
     * @return taramalar, özet, filtre dropdown ve toplam kart bilgisi
     */
    public TaramalarResponseDto getTaramalar(LocalDate startDate, LocalDate endDate,
                                              String screener, String stock) {
        List<ScreenerResultModel> results = screenerResultRepository
                .findTelegramSentBetweenDays(startDate, endDate);

        // Tarama adı filtresi (contains, case-insensitive)
        if (screener != null && !screener.isBlank()) {
            String filter = screener.toUpperCase();
            results = results.stream()
                    .filter(r -> r.getScreenerName() != null &&
                            r.getScreenerName().toUpperCase().contains(filter))
                    .toList();
        }

        // Hisse kodu filtresi (contains, case-insensitive)
        if (stock != null && !stock.isBlank()) {
            String filter = stock.toUpperCase();
            results = results.stream()
                    .filter(r -> r.getStockName() != null &&
                            r.getStockName().toUpperCase().contains(filter))
                    .toList();
        }

        // DTO dönüşümü + gruplama
        List<TaramaDto> taramalar = buildTaramalarWithGrouping(results);

        // Özet istatistikler
        TaramaOzetDto ozet = buildOzet(results);

        // Filtre dropdown için benzersiz tarama adları (filtrelenmiş sonuçlardan türet)
        List<String> screenerNames = results.stream()
                .map(ScreenerResultModel::getScreenerName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        return TaramalarResponseDto.builder()
                .taramalar(taramalar)
                .ozet(ozet)
                .screenerNames(screenerNames)
                .toplamKart(taramalar.size())
                .build();
    }

    /**
     * Tarama sonuçlarını hisse+gün+zaman bazında gruplar ve DTO listesine dönüştürür.
     *
     * <p>Gruplama anahtarı: {@code stockName|screenerDay|screenerTime}. Aynı hisse+gün+zaman
     * kombinasyonunda birden fazla tarama varsa tek bir kart olarak {@code grouped=true}
     * ve screenerName "N Tarama" gösterilir.</p>
     *
     * @param results entity listesi
     * @return gruplu veya tekil DTO listesi
     */
    private List<TaramaDto> buildTaramalarWithGrouping(List<ScreenerResultModel> results) {
        // stockName|screenerDay|screenerTime anahtarına göre grupla (sıra korunsun)
        Map<String, List<ScreenerResultModel>> grouped = results.stream()
                .collect(Collectors.groupingBy(
                        r -> (r.getStockName() != null ? r.getStockName() : "") + "|" +
                             (r.getScreenerDay() != null ? r.getScreenerDay().format(DATE_FORMATTER) : "") + "|" +
                             (r.getScreenerTime() != null ? r.getScreenerTime().format(TIME_FORMATTER) : ""),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return grouped.values().stream()
                .map(group -> {
                    ScreenerResultModel first = group.get(0);
                    if (group.size() > 1) {
                        return toDto(first, true, group.size() + " Tarama");
                    } else {
                        return toDto(first, false, first.getScreenerName());
                    }
                })
                .toList();
    }

    /**
     * Entity'den DTO'ya dönüşüm yapar.
     *
     * @param model kaynak entity
     * @param isGrouped gruplu mu
     * @param screenerNameOverride gösterilecek tarama adı
     * @return dönüştürülmüş DTO
     */
    private TaramaDto toDto(ScreenerResultModel model, boolean isGrouped, String screenerNameOverride) {
        return TaramaDto.builder()
                .id(model.getId())
                .stockName(model.getStockName())
                .price(model.getPrice())
                .percentage(model.getPercentage())
                .screenerName(screenerNameOverride)
                .screenerType(model.getScreenerType() != null ? model.getScreenerType().name() : null)
                .screenerTime(model.getScreenerTime() != null ? model.getScreenerTime().format(TIME_FORMATTER) : null)
                .screenerDay(model.getScreenerDay() != null ? model.getScreenerDay().format(DATE_FORMATTER) : null)
                .gunSonuDegisim(model.getGunSonuDegisim())
                .grouped(isGrouped)
                .build();
    }

    /**
     * Tarama sonuçlarından özet istatistikleri hesaplar.
     *
     * <p>Gün sonu değişimi olan kayıtlardan ortalama getiri, başarı oranı,
     * max/min getiri hesaplanır. Değişim verisi olmayan kayıtlar istatistikten hariç tutulur.</p>
     *
     * @param results entity listesi
     * @return özet istatistik DTO
     */
    private TaramaOzetDto buildOzet(List<ScreenerResultModel> results) {
        // gunSonuDegisim null olmayanları filtrele
        List<Double> degisimler = results.stream()
                .map(ScreenerResultModel::getGunSonuDegisim)
                .filter(d -> d != null)
                .toList();

        long pozitifSayisi = degisimler.stream().filter(d -> d >= 0).count();
        long negatifSayisi = degisimler.stream().filter(d -> d < 0).count();
        long benzersizHisse = results.stream()
                .map(ScreenerResultModel::getStockName)
                .filter(s -> s != null)
                .distinct()
                .count();
        long taramaSayisi = results.stream()
                .map(ScreenerResultModel::getScreenerName)
                .filter(s -> s != null)
                .distinct()
                .count();

        Double ortalamaGetiri = null;
        Double basariOrani = null;
        Double maxGetiri = null;
        Double minGetiri = null;

        if (!degisimler.isEmpty()) {
            DoubleSummaryStatistics stats = degisimler.stream()
                    .mapToDouble(Double::doubleValue)
                    .summaryStatistics();
            ortalamaGetiri = stats.getAverage();
            maxGetiri = stats.getMax();
            minGetiri = stats.getMin();
            basariOrani = (double) pozitifSayisi / degisimler.size() * 100;
        }

        return TaramaOzetDto.builder()
                .toplamSinyal(results.size())
                .ortalamaGetiri(ortalamaGetiri)
                .basariOrani(basariOrani)
                .pozitifSayisi((int) pozitifSayisi)
                .negatifSayisi((int) negatifSayisi)
                .benzersizHisseSayisi((int) benzersizHisse)
                .taramaSayisi((int) taramaSayisi)
                .maxGetiri(maxGetiri)
                .minGetiri(minGetiri)
                .build();
    }
}
