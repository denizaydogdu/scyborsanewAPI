package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.analyst.AnalystRatingDto;
import com.scyborsa.api.dto.enrichment.BrokerageRating;
import com.scyborsa.api.service.AnalystRatingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fintables aracı kurum analist tavsiyesi servis implementasyonu.
 *
 * <p>{@link AnalystRatingService}'ten alınan cache'li analist tavsiye verisini
 * Telegram mesajlarında kullanılmak üzere {@link BrokerageRating} listesine dönüştürür.</p>
 *
 * <p>Sadece model portföy tavsiyelerini ({@code inModelPortfolio = true}) filtreler,
 * yayın tarihine göre sıralar ve en fazla 5 tavsiye döner.</p>
 *
 * <p>Tüm hatalar yakalanır, boş liste döner — graceful degradation.</p>
 *
 * @see AnalystRatingService
 * @see BrokerageRating
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FintablesAnalystServiceImpl implements FintablesAnalystService {

    /** Analist tavsiye cache servisi. */
    private final AnalystRatingService analystRatingService;

    /** Döndürülecek maksimum tavsiye sayısı. */
    private static final int MAX_RESULTS = 5;

    /**
     * Hissenin model portföy tavsiyelerini getirir.
     *
     * <p>{@link AnalystRatingService}'ten tüm tavsiyeleri alır,
     * hisse koduna ve model portföy durumuna göre filtreler,
     * yayın tarihine göre azalan sırada sıralar ve en fazla 5 tavsiye döner.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return model portföy tavsiye listesi; hata durumunda boş liste
     */
    @Override
    public List<BrokerageRating> getModelPortfolio(String stockName) {
        try {
            List<AnalystRatingDto> allRatings = analystRatingService.getAnalystRatings();
            if (allRatings == null || allRatings.isEmpty()) {
                log.debug("[FintablesAnalyst] Analist tavsiyeleri boş: stockName={}", stockName);
                return Collections.emptyList();
            }

            List<BrokerageRating> result = allRatings.stream()
                    .filter(r -> stockName.equalsIgnoreCase(r.getStockCode()))
                    .filter(AnalystRatingDto::isModelPortfolio)
                    .sorted(Comparator.comparing(
                            AnalystRatingDto::getDate,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(MAX_RESULTS)
                    .map(this::toBrokerageRating)
                    .collect(Collectors.toList());

            log.debug("[FintablesAnalyst] {} model portföy tavsiyesi döndü: stockName={}",
                    result.size(), stockName);
            return result;
        } catch (Exception e) {
            log.error("[FintablesAnalyst] Model portföy tavsiyeleri alınırken hata: stockName={}",
                    stockName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Analist tavsiye DTO'sunu {@link BrokerageRating} nesnesine dönüştürür.
     *
     * @param rating analist tavsiye verisi
     * @return Telegram formatına uygun kurum tavsiye bilgisi
     */
    private BrokerageRating toBrokerageRating(AnalystRatingDto rating) {
        String brokerName = null;
        if (rating.getBrokerage() != null) {
            brokerName = rating.getBrokerage().getShortTitle();
            if (brokerName == null || brokerName.isBlank()) {
                brokerName = rating.getBrokerage().getTitle();
            }
        }

        return BrokerageRating.builder()
                .brokerName(brokerName)
                .targetPrice(rating.getTargetPrice())
                .build();
    }
}
