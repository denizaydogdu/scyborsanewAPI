package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.TakasCustodianDTO;
import com.scyborsa.api.dto.enrichment.TakasResponseDto;
import com.scyborsa.api.dto.enrichment.TakasResponseDto.TakasCustodianEnrichedDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Takas/saklama dağılımı servis implementasyonu.
 *
 * <p>{@link TakasService} üzerinden alınan zenginleştirilmiş Takas verisini
 * Telegram mesajlarında kullanılmak üzere {@link TakasCustodianDTO} listesine dönüştürür.</p>
 *
 * <p>Tüm hatalar yakalanır, boş liste döner — graceful degradation.</p>
 *
 * @see TakasService
 * @see TakasCustodianDTO
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TakasApiServiceImpl implements TakasApiService {

    /** Takas iş mantığı servisi. */
    private final TakasService takasService;

    /**
     * Hissenin saklama kuruluşu dağılımını getirir.
     *
     * <p>{@link TakasService}'ten alınan zenginleştirilmiş veriyi
     * basit {@link TakasCustodianDTO} listesine dönüştürür.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @param date      tarih (kullanılmaz — TakasService zaman bazlı strateji uygular)
     * @return saklama dağılım listesi; hata durumunda boş liste
     */
    @Override
    public List<TakasCustodianDTO> getCustodyData(String stockName, LocalDate date) {
        try {
            TakasResponseDto takasData = takasService.getTakasDistribution(stockName);
            if (takasData == null || takasData.getCustodians() == null) {
                log.debug("[TakasApi] Takas verisi bulunamadı: stockName={}", stockName);
                return Collections.emptyList();
            }

            List<TakasCustodianDTO> result = takasData.getCustodians().stream()
                    .map(this::toSimpleDto)
                    .collect(Collectors.toList());

            log.debug("[TakasApi] {} saklama kuruluşu döndü: stockName={}", result.size(), stockName);
            return result;
        } catch (Exception e) {
            log.error("[TakasApi] Saklama dağılımı alınırken hata: stockName={}", stockName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Servisin aktif olup olmadığını döner.
     *
     * @return her zaman {@code true} — TakasService mevcut ve çalışır durumda
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Zenginleştirilmiş Takas DTO'sunu basit {@link TakasCustodianDTO} nesnesine dönüştürür.
     *
     * <p>Yüzde değeri 0-100 aralığından 0.0-1.0 aralığına dönüştürülür
     * (TakasCustodianDTO beklentisi: 0.15 = %15).</p>
     *
     * @param enriched zenginleştirilmiş Takas DTO'su
     * @return basit Takas DTO'su
     */
    private TakasCustodianDTO toSimpleDto(TakasCustodianEnrichedDto enriched) {
        return TakasCustodianDTO.builder()
                .custodianCode(enriched.getCode())
                .value(enriched.getDeger())
                .percentage(enriched.getYuzde() / 100.0)
                .build();
    }
}
