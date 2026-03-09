package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.AkdBrokerDto;
import com.scyborsa.api.dto.enrichment.AkdResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesAkdResponseDto;
import com.scyborsa.api.model.AraciKurum;
import com.scyborsa.api.repository.AraciKurumRepository;
import com.scyborsa.api.service.FintablesApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AKD (Aracı Kurum Dağılımı) iş mantığı servisi.
 * Fintables API'den ham veriyi alır, DB'deki {@link AraciKurum} entity'leri ile
 * zenginleştirir ve alıcı/satıcı/toplam olarak ayrıştırır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AkdService {

    private final FintablesApiClient fintablesApiClient;
    private final AraciKurumRepository araciKurumRepository;

    /**
     * Hisse bazlı AKD dağılımını getirir.
     * <p>
     * Fintables API'den bugünün verisini çeker, aracı kurum kodlarını DB'den
     * isim/logo ile eşleştirir, net pozisyona göre alıcı/satıcı ayırır ve
     * "Diğer" satırını hesaplar.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş AKD dağılımı (alıcılar, satıcılar, toplam)
     */
    public AkdResponseDto getAkdDistribution(String stockCode) {
        try {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            FintablesAkdResponseDto raw = fintablesApiClient.getAkd(stockCode, today, today);

            if (raw == null || raw.getResults() == null || raw.getResults().isEmpty()) {
                return emptyResponse();
            }

            // Batch code → entity lookup (N+1 prevention)
            Set<String> codes = raw.getResults().stream()
                    .map(FintablesAkdResponseDto.AkdItem::getBrokerage)
                    .collect(Collectors.toSet());
            Map<String, AraciKurum> kurumMap = araciKurumRepository.findByCodeIn(codes)
                    .stream()
                    .collect(Collectors.toMap(AraciKurum::getCode, Function.identity()));

            // Alıcılar: net.size > 0, sorted desc
            List<AkdBrokerDto> alicilar = raw.getResults().stream()
                    .filter(i -> i.getNet() != null && i.getNet().getSize() > 0)
                    .sorted(Comparator.comparingLong((FintablesAkdResponseDto.AkdItem i) -> i.getNet().getSize()).reversed())
                    .map(i -> toDto(i, i.getNet(), kurumMap))
                    .collect(Collectors.toCollection(ArrayList::new));

            // Satıcılar: net.size < 0, sorted desc by abs
            List<AkdBrokerDto> saticilar = raw.getResults().stream()
                    .filter(i -> i.getNet() != null && i.getNet().getSize() < 0)
                    .sorted(Comparator.comparingLong((FintablesAkdResponseDto.AkdItem i) -> i.getNet().getSize()))
                    .map(i -> toDto(i, i.getNet(), kurumMap))
                    .collect(Collectors.toCollection(ArrayList::new));
            // Satıcılar: adet'i pozitif yap (abs)
            saticilar.forEach(s -> s.setAdet(Math.abs(s.getAdet())));

            // Toplam: tümü, sorted desc by total.size
            List<AkdBrokerDto> toplam = raw.getResults().stream()
                    .filter(i -> i.getTotal() != null)
                    .sorted(Comparator.comparingLong((FintablesAkdResponseDto.AkdItem i) -> i.getTotal().getSize()).reversed())
                    .map(i -> toDto(i, i.getTotal(), kurumMap))
                    .collect(Collectors.toCollection(ArrayList::new));
            appendDiger(toplam);

            return AkdResponseDto.builder()
                    .alicilar(alicilar)
                    .saticilar(saticilar)
                    .toplam(toplam)
                    .build();

        } catch (Exception e) {
            log.error("[AKD] Veri alınırken hata: stockCode={}", stockCode, e);
            return emptyResponse();
        }
    }

    /**
     * Ham AKD item ve metrik verisini zenginleştirilmiş DTO'ya dönüştürür.
     *
     * @param item     Fintables AKD item
     * @param metric   kullanılacak metrik (net veya total)
     * @param kurumMap kod → AraciKurum eşleme haritası
     * @return zenginleştirilmiş broker DTO
     */
    private AkdBrokerDto toDto(FintablesAkdResponseDto.AkdItem item,
                               FintablesAkdResponseDto.AkdMetric metric,
                               Map<String, AraciKurum> kurumMap) {
        AraciKurum kurum = kurumMap.get(item.getBrokerage());
        return AkdBrokerDto.builder()
                .code(item.getBrokerage())
                .title(kurum != null ? kurum.getTitle() : item.getBrokerage())
                .shortTitle(kurum != null ? kurum.getShortTitle() : item.getBrokerage())
                .logoUrl(kurum != null ? kurum.getLogoUrl() : null)
                .adet(metric.getSize())
                .yuzde(metric.getPercentage() * 100)
                .maliyet(metric.getCost())
                .build();
    }

    /**
     * "Diğer" satırını listeye ekler.
     * Toplam yüzde 100'den küçükse kalan yüzdelik "Diğer" olarak eklenir.
     *
     * @param list kurum listesi
     */
    private void appendDiger(List<AkdBrokerDto> list) {
        if (list.isEmpty()) return;
        double toplamYuzde = list.stream().mapToDouble(AkdBrokerDto::getYuzde).sum();
        double kalanYuzde = 100.0 - toplamYuzde;
        if (kalanYuzde > 0.01) {
            list.add(AkdBrokerDto.builder()
                    .code("DIGER")
                    .title("Diğer")
                    .shortTitle("Diğer")
                    .adet(0)
                    .yuzde(kalanYuzde)
                    .maliyet(0)
                    .build());
        }
    }

    /**
     * Boş AKD response oluşturur.
     *
     * @return tüm listeleri boş olan response
     */
    private AkdResponseDto emptyResponse() {
        return AkdResponseDto.builder()
                .alicilar(List.of())
                .saticilar(List.of())
                .toplam(List.of())
                .build();
    }
}
