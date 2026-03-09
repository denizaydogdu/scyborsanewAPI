package com.scyborsa.api.controller;

import com.scyborsa.api.dto.HaberDetayDto;
import com.scyborsa.api.dto.KapHaberDto;
import com.scyborsa.api.dto.kap.KapNewsResponseDto;
import com.scyborsa.api.model.HaberDetay;
import com.scyborsa.api.repository.HaberDetayRepository;
import com.scyborsa.api.service.HaberDetailFetcher;
import com.scyborsa.api.service.KapHaberService;
import com.scyborsa.api.service.kap.KapNewsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * KAP (Kamuyu Aydınlatma Platformu) haberlerini sunan REST controller.
 *
 * <p>İki farklı veri kaynağından KAP haberleri sunar:</p>
 * <ul>
 *   <li>{@code GET /api/v1/kap/haberler} — Veritabanindaki KAP bildirimleri</li>
 *   <li>{@code GET /api/v1/kap/news} — TradingView canli KAP haberleri</li>
 *   <li>{@code GET /api/v1/kap/market-news} — TradingView canli piyasa haberleri</li>
 *   <li>{@code GET /api/v1/kap/world-news} — TradingView canli dunya haberleri</li>
 *   <li>{@code GET /api/v1/kap/haber/{newsId}} — Tek haber detayi (on-demand fetch)</li>
 * </ul>
 *
 * @see KapHaberService
 * @see KapNewsClient
 */
@RestController
@RequestMapping("/api/v1/kap")
@RequiredArgsConstructor
public class KapHaberController {

    private final KapHaberService kapHaberService;
    private final KapNewsClient kapNewsClient;
    private final HaberDetayRepository haberDetayRepository;
    private final HaberDetailFetcher haberDetailFetcher;

    private static final DateTimeFormatter TR_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", new Locale("tr", "TR"));

    /**
     * En son KAP haberlerini veritabanından listeler.
     *
     * <p>HTTP GET {@code /api/v1/kap/haberler}</p>
     *
     * @return son KAP bildirimlerinin listesi
     */
    @GetMapping("/haberler")
    public List<KapHaberDto> getLatestKapHaberler() {
        return kapHaberService.getLatestKapHaberler();
    }

    /**
     * TradingView news-mediator API'sinden canlı KAP haberlerini getirir.
     *
     * <p>HTTP GET {@code /api/v1/kap/news}</p>
     *
     * <p>API erişilemezse boş liste içeren response döner (graceful degradation).</p>
     *
     * @return canlı KAP haberleri; hata durumunda boş items listesi
     */
    @GetMapping("/news")
    public KapNewsResponseDto getKapNews() {
        return orEmpty(kapNewsClient.fetchKapNews());
    }

    /**
     * TradingView headlines API'sinden piyasa haberlerini getirir.
     *
     * <p>HTTP GET {@code /api/v1/kap/market-news}</p>
     *
     * <p>API erişilemezse boş liste içeren response döner (graceful degradation).</p>
     *
     * @return piyasa haberleri; hata durumunda boş items listesi
     */
    @GetMapping("/market-news")
    public KapNewsResponseDto getMarketNews() {
        return orEmpty(kapNewsClient.fetchMarketNews());
    }

    /**
     * TradingView news-mediator API'sinden dünya haberlerini getirir.
     *
     * <p>HTTP GET {@code /api/v1/kap/world-news}</p>
     *
     * <p>API erişilemezse boş liste içeren response döner (graceful degradation).</p>
     *
     * @return dünya haberleri; hata durumunda boş items listesi
     */
    @GetMapping("/world-news")
    public KapNewsResponseDto getWorldNews() {
        return orEmpty(kapNewsClient.fetchWorldNews());
    }

    /**
     * Tek haber detayini newsId ile dondurur.
     *
     * <p>HTTP GET {@code /api/v1/kap/haber/{newsId}}</p>
     *
     * <p>Detay henuz cekilmemisse on-demand fetch yapar. Haber bulunamazsa 404 doner.</p>
     *
     * @param newsId TradingView haber kimligi
     * @return haber detayi veya 404
     */
    @GetMapping("/haber/{newsId}")
    public ResponseEntity<HaberDetayDto> getHaberDetay(@PathVariable String newsId) {
        // newsId format dogrulama — SSRF ve log injection onlemi
        if (newsId == null || !newsId.matches("[a-zA-Z0-9_\\-:.]{1,200}")) {
            return ResponseEntity.badRequest().build();
        }

        return haberDetayRepository.findByNewsId(newsId)
                .map(haber -> {
                    // On-demand fetch if not yet fetched
                    if (!haber.isFetched()) {
                        haberDetailFetcher.fetchDetailOnDemand(haber);
                        haber = haberDetayRepository.findByNewsId(newsId).orElse(haber);
                    }
                    return ResponseEntity.ok(toDto(haber));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Null response'u boş items listesi içeren response'a dönüştürür.
     *
     * @param response API'den dönen response (nullable)
     * @return aynı response veya boş items listesi içeren yeni response
     */
    private KapNewsResponseDto orEmpty(KapNewsResponseDto response) {
        if (response == null) {
            KapNewsResponseDto empty = new KapNewsResponseDto();
            empty.setItems(List.of());
            return empty;
        }
        return response;
    }

    /**
     * HaberDetay entity'sini DTO'ya donusturur.
     *
     * @param haber donusturulecek entity
     * @return DTO karsiligi
     */
    private HaberDetayDto toDto(HaberDetay haber) {
        return HaberDetayDto.builder()
                .id(haber.getId())
                .newsId(haber.getNewsId())
                .title(haber.getTitle())
                .provider(haber.getProvider())
                .detailContent(haber.getDetailContent())
                .shortDescription(haber.getShortDescription())
                .originalKapUrl(haber.getOriginalKapUrl())
                .publishedFormatted(haber.getPublished() != null
                        ? haber.getPublished().format(TR_FORMATTER) : null)
                .newsType(haber.getNewsType())
                .fetched(haber.isFetched())
                .build();
    }
}
