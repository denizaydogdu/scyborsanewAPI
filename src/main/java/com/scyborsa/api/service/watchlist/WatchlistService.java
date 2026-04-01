package com.scyborsa.api.service.watchlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.dto.watchlist.*;
import com.scyborsa.api.model.AppUser;
import com.scyborsa.api.model.Watchlist;
import com.scyborsa.api.model.WatchlistItem;
import com.scyborsa.api.repository.AppUserRepository;
import com.scyborsa.api.repository.WatchlistItemRepository;
import com.scyborsa.api.repository.WatchlistRepository;
import com.scyborsa.api.service.chart.QuotePriceCache;
import com.scyborsa.api.service.screener.TradingViewScreenerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Takip listesi CRUD servis sinifi.
 *
 * <p>Kullanicilarin takip listesi olusturma, guncelleme, silme ve hisse ekleme/cikarma
 * islemlerini gerceklestirir. Her kullanici en fazla {@value #MAX_WATCHLISTS_PER_USER}
 * takip listesi olusturabilir ve her listede en fazla {@value #MAX_STOCKS_PER_WATCHLIST}
 * hisse bulunabilir.</p>
 *
 * <p>{@link QuotePriceCache} opsiyonel olarak enjekte edilir; mevcut degilse
 * anlik fiyat zenginlestirmesi yapilmaz (graceful degradation).</p>
 *
 * @see Watchlist
 * @see WatchlistItem
 * @see WatchlistRepository
 * @see WatchlistItemRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    /** Kullanici basina maksimum takip listesi sayisi. */
    private static final int MAX_WATCHLISTS_PER_USER = 10;

    /** Takip listesi basina maksimum hisse sayisi. */
    private static final int MAX_STOCKS_PER_WATCHLIST = 50;

    /** Varsayilan takip listesi adi. */
    private static final String DEFAULT_WATCHLIST_NAME = "Takip Listem";

    private final WatchlistRepository watchlistRepository;
    private final WatchlistItemRepository watchlistItemRepository;
    private final AppUserRepository userRepository;

    /** Anlik fiyat cache — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private QuotePriceCache quotePriceCache;

    /** Broadcast servisi — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private WatchlistBroadcastService broadcastService;

    /** TradingView Scanner API client — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private TradingViewScreenerClient screenerClient;

    /** JSON islemleri icin ObjectMapper. */
    @Autowired
    private ObjectMapper objectMapper;

    // ==================== WATCHLIST CRUD ====================

    /**
     * Kullanicinin tum aktif takip listelerini getirir.
     *
     * <p>Kullanicinin hic listesi yoksa otomatik olarak varsayilan liste olusturulur
     * ve tek elemanli liste doner.</p>
     *
     * @param userId kullanici ID'si
     * @return aktif takip listesi DTO listesi
     */
    @Transactional
    public List<WatchlistDto> getUserWatchlists(Long userId) {
        List<Watchlist> watchlists = watchlistRepository.findByUserIdAndAktifTrueOrderByDisplayOrderAsc(userId);

        if (watchlists.isEmpty()) {
            WatchlistDto defaultList = getOrCreateDefaultWatchlist(userId);
            return List.of(defaultList);
        }

        return watchlists.stream()
                .map(this::toWatchlistDto)
                .toList();
    }

    /**
     * Kullanicinin varsayilan takip listesini getirir veya yoksa olusturur.
     *
     * @param userId kullanici ID'si
     * @return varsayilan takip listesi DTO'su
     */
    @Transactional
    public WatchlistDto getOrCreateDefaultWatchlist(Long userId) {
        return watchlistRepository.findByUserIdAndIsDefaultTrueAndAktifTrue(userId)
                .map(this::toWatchlistDto)
                .orElseGet(() -> {
                    AppUser user = userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + userId));

                    Watchlist watchlist = Watchlist.builder()
                            .user(user)
                            .userEmail(user.getEmail())
                            .name(DEFAULT_WATCHLIST_NAME)
                            .isDefault(true)
                            .displayOrder(0)
                            .build();

                    Watchlist saved = watchlistRepository.save(watchlist);
                    log.info("Varsayilan takip listesi olusturuldu: userId={}, watchlistId={}",
                            userId, saved.getId());

                    return toWatchlistDto(saved);
                });
    }

    /**
     * Yeni bir takip listesi olusturur.
     *
     * <p>Kullanici basina en fazla {@value #MAX_WATCHLISTS_PER_USER} takip listesi
     * olusturulabilir. Ayni kullanicida ayni isimde aktif liste olamaz.</p>
     *
     * @param userId kullanici ID'si
     * @param req    liste olusturma istegi
     * @return olusturulan takip listesi DTO'su
     * @throws RuntimeException limit asildiginda veya isim duplikasyonunda
     */
    @Transactional
    public WatchlistDto createWatchlist(Long userId, CreateWatchlistRequest req) {
        // Limit kontrol
        long count = watchlistRepository.countByUserIdAndAktifTrue(userId);
        if (count >= MAX_WATCHLISTS_PER_USER) {
            throw new RuntimeException("Maksimum " + MAX_WATCHLISTS_PER_USER + " takip listesi olusturabilirsiniz");
        }

        // Isim duplikat kontrol
        if (watchlistRepository.existsByUserIdAndNameIgnoreCaseAndAktifTrue(userId, req.getName().trim())) {
            throw new RuntimeException("Bu isimde bir takip listeniz zaten mevcut: " + req.getName().trim());
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + userId));

        int nextOrder = watchlistRepository.findMaxDisplayOrderByUserId(userId) + 1;

        Watchlist watchlist = Watchlist.builder()
                .user(user)
                .userEmail(user.getEmail())
                .name(req.getName().trim())
                .description(req.getDescription() != null ? req.getDescription().trim() : null)
                .displayOrder(nextOrder)
                .isDefault(false)
                .build();

        Watchlist saved = watchlistRepository.save(watchlist);
        log.info("Takip listesi olusturuldu: userId={}, watchlistId={}, name={}",
                userId, saved.getId(), saved.getName());

        return toWatchlistDto(saved);
    }

    /**
     * Mevcut bir takip listesini gunceller.
     *
     * <p>Sadece listenin adi ve aciklamasi guncellenebilir. Isim degisikligi durumunda
     * ayni kullanicida duplikat isim kontrolu yapilir (kendisi haric).</p>
     *
     * @param userId      kullanici ID'si (sahiplik dogrulamasi icin)
     * @param watchlistId takip listesi ID'si
     * @param req         guncelleme istegi
     * @return guncellenmis takip listesi DTO'su
     * @throws RuntimeException liste bulunamazsa, sahiplik dogrulanmazsa veya isim duplikat ise
     */
    @Transactional
    public WatchlistDto updateWatchlist(Long userId, Long watchlistId, CreateWatchlistRequest req) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Takip listesi bulunamadi: " + watchlistId));

        verifyOwnership(watchlist, userId);

        if (!watchlist.getAktif()) {
            throw new RuntimeException("Pasif takip listesi guncellenemez");
        }

        // Isim degismis mi ve duplikat mi kontrol (kendisi haric)
        String newName = req.getName().trim();
        if (!watchlist.getName().equalsIgnoreCase(newName)) {
            if (watchlistRepository.existsByUserIdAndNameIgnoreCaseAndAktifTrueExcluding(userId, newName, watchlistId)) {
                throw new RuntimeException("Bu isimde bir takip listeniz zaten mevcut: " + newName);
            }
        }

        watchlist.setName(newName);
        watchlist.setDescription(req.getDescription() != null ? req.getDescription().trim() : null);

        Watchlist saved = watchlistRepository.save(watchlist);
        log.info("Takip listesi guncellendi: userId={}, watchlistId={}, name={}",
                userId, watchlistId, saved.getName());

        return toWatchlistDto(saved);
    }

    /**
     * Bir takip listesini soft delete ile siler.
     *
     * <p>Varsayilan takip listesi silinemez. Silme islemi {@code aktif = false}
     * olarak gerceklesir (soft delete). Broadcast abonelikleri de kaldirilir.</p>
     *
     * @param userId      kullanici ID'si (sahiplik dogrulamasi icin)
     * @param watchlistId silinecek takip listesi ID'si
     * @throws RuntimeException liste bulunamazsa, sahiplik dogrulanmazsa veya varsayilan liste ise
     */
    @Transactional
    public void deleteWatchlist(Long userId, Long watchlistId) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Takip listesi bulunamadi: " + watchlistId));

        verifyOwnership(watchlist, userId);

        if (watchlist.getIsDefault()) {
            throw new RuntimeException("Varsayilan takip listesi silinemez");
        }

        // Soft delete
        watchlist.setAktif(false);
        watchlistRepository.save(watchlist);

        // Broadcast aboneliklerini kaldir — sadece baska aktif listede olmayan hisseler icin
        if (broadcastService != null && watchlist.getItems() != null) {
            Long ownerId = watchlist.getUser().getId();
            for (WatchlistItem item : watchlist.getItems()) {
                boolean existsElsewhere = watchlistItemRepository.existsByStockCodeInOtherActiveWatchlists(
                        item.getStockCode(), ownerId, watchlistId);
                if (!existsElsewhere) {
                    broadcastService.removeSubscription(item.getStockCode(), watchlist.getUserEmail());
                }
            }
        }

        log.info("Takip listesi silindi: userId={}, watchlistId={}", userId, watchlistId);
    }

    // ==================== STOCK CRUD ====================

    /**
     * Bir takip listesindeki hisseleri anlik fiyat bilgileriyle birlikte getirir.
     *
     * <p>Hisseler siralama degerine gore artan sirada doner. Anlik fiyat, degisim,
     * degisim yuzdesi ve hacim bilgileri {@link QuotePriceCache}'ten zenginlestirilir.</p>
     *
     * @param userId      kullanici ID'si (sahiplik dogrulamasi icin)
     * @param watchlistId takip listesi ID'si
     * @return hisse DTO listesi (fiyat zenginlestirilmis)
     * @throws RuntimeException liste bulunamazsa veya sahiplik dogrulanmazsa
     */
    @Transactional(readOnly = true)
    public List<WatchlistStockDto> getWatchlistStocks(Long userId, Long watchlistId) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Takip listesi bulunamadi: " + watchlistId));

        verifyOwnership(watchlist, userId);

        if (!watchlist.getAktif()) {
            throw new RuntimeException("Pasif takip listesinin hisseleri goruntulenemez");
        }

        List<WatchlistItem> items = watchlistItemRepository.findByWatchlistIdOrderByDisplayOrderAsc(watchlistId);

        List<WatchlistStockDto> result = items.stream()
                .map(this::toStockDto)
                .map(this::enrichWithLivePrice)
                .collect(Collectors.toList());

        return enrichWithScannerFallback(result);
    }

    /**
     * Bir takip listesine hisse ekler.
     *
     * <p>Liste basina en fazla {@value #MAX_STOCKS_PER_WATCHLIST} hisse eklenebilir.
     * Ayni listede ayni hisse kodu tekrar edemez. Hisse kodu buyuk harfe normalize edilir.</p>
     *
     * @param userId      kullanici ID'si (sahiplik dogrulamasi icin)
     * @param watchlistId takip listesi ID'si
     * @param req         hisse ekleme istegi
     * @return eklenen hissenin DTO'su (fiyat zenginlestirilmis)
     * @throws RuntimeException liste bulunamazsa, limit asildiginda veya duplikat hisse ise
     */
    @Transactional
    public WatchlistStockDto addStock(Long userId, Long watchlistId, AddStockRequest req) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Takip listesi bulunamadi: " + watchlistId));

        verifyOwnership(watchlist, userId);

        if (!watchlist.getAktif()) {
            throw new RuntimeException("Pasif takip listesine hisse eklenemez");
        }

        // Limit kontrol
        long itemCount = watchlistItemRepository.countByWatchlistId(watchlistId);
        if (itemCount >= MAX_STOCKS_PER_WATCHLIST) {
            throw new RuntimeException("Bir takip listesinde en fazla " + MAX_STOCKS_PER_WATCHLIST + " hisse olabilir");
        }

        String stockCode = req.getStockCode().trim().toUpperCase();

        // Duplikat kontrol
        if (watchlistItemRepository.existsByWatchlistIdAndStockCode(watchlistId, stockCode)) {
            throw new RuntimeException("Bu hisse zaten listede mevcut: " + stockCode);
        }

        // Siralama: mevcut item'larin max displayOrder + 1
        List<WatchlistItem> existingItems = watchlistItemRepository.findByWatchlistIdOrderByDisplayOrderAsc(watchlistId);
        int nextOrder = existingItems.isEmpty() ? 0 :
                existingItems.stream()
                        .mapToInt(WatchlistItem::getDisplayOrder)
                        .max()
                        .orElse(0) + 1;

        WatchlistItem item = WatchlistItem.builder()
                .watchlist(watchlist)
                .stockCode(stockCode)
                .stockName(req.getStockName())
                .displayOrder(nextOrder)
                .build();

        WatchlistItem saved = watchlistItemRepository.save(item);
        log.info("Hisse takip listesine eklendi: userId={}, watchlistId={}, stockCode={}",
                userId, watchlistId, stockCode);

        // Broadcast aboneligi ekle
        if (broadcastService != null) {
            broadcastService.addSubscription(stockCode, watchlist.getUserEmail());
        }

        WatchlistStockDto dto = enrichWithLivePrice(toStockDto(saved));

        // Seans kapalıysa Scanner fallback ile fiyat çek
        if (dto.getLastPrice() == null) {
            List<WatchlistStockDto> singleList = new java.util.ArrayList<>();
            singleList.add(dto);
            enrichWithScannerFallback(singleList);
        }

        return dto;
    }

    /**
     * Bir takip listesinden hisse cikarir.
     *
     * @param userId      kullanici ID'si (sahiplik dogrulamasi icin)
     * @param watchlistId takip listesi ID'si
     * @param stockCode   cikarilacak hisse kodu
     * @throws RuntimeException liste bulunamazsa veya sahiplik dogrulanmazsa
     */
    @Transactional
    public void removeStock(Long userId, Long watchlistId, String stockCode) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Takip listesi bulunamadi: " + watchlistId));

        verifyOwnership(watchlist, userId);

        String normalizedCode = stockCode.trim().toUpperCase();
        watchlistItemRepository.deleteByWatchlistIdAndStockCode(watchlistId, normalizedCode);

        // Broadcast aboneligini kaldir — sadece baska aktif listede olmayan hisseler icin
        if (broadcastService != null) {
            boolean existsElsewhere = watchlistItemRepository.existsByStockCodeInOtherActiveWatchlists(
                    normalizedCode, watchlist.getUser().getId(), watchlistId);
            if (!existsElsewhere) {
                broadcastService.removeSubscription(normalizedCode, watchlist.getUserEmail());
            }
        }

        log.info("Hisse takip listesinden cikarildi: userId={}, watchlistId={}, stockCode={}",
                userId, watchlistId, normalizedCode);
    }

    /**
     * Bir takip listesindeki hisselerin siralamasini gunceller.
     *
     * <p>{@code itemIds} listesindeki sira, yeni displayOrder degerlerini belirler.
     * Liste indeksi (0-based) displayOrder olarak atanir.</p>
     *
     * @param userId      kullanici ID'si (sahiplik dogrulamasi icin)
     * @param watchlistId takip listesi ID'si
     * @param req         siralama degistirme istegi (yeni siraya gore item ID listesi)
     * @throws RuntimeException liste bulunamazsa veya sahiplik dogrulanmazsa
     */
    @Transactional
    public void reorderStocks(Long userId, Long watchlistId, ReorderRequest req) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Takip listesi bulunamadi: " + watchlistId));

        verifyOwnership(watchlist, userId);

        // Validate all item IDs belong to this watchlist
        List<WatchlistItem> items = watchlistItemRepository.findByWatchlistIdOrderByDisplayOrderAsc(watchlistId);
        Set<Long> validIds = items.stream().map(WatchlistItem::getId).collect(Collectors.toSet());
        for (Long itemId : req.getItemIds()) {
            if (!validIds.contains(itemId)) {
                throw new RuntimeException("Gecersiz siralama: Oge bu takip listesine ait degil (id=" + itemId + ")");
            }
        }

        if (req.getItemIds().size() != items.size()) {
            throw new RuntimeException("Sıralama listesi tam olmalı: beklenen " + items.size() + ", gelen " + req.getItemIds().size());
        }

        List<Long> itemIds = req.getItemIds();
        for (int i = 0; i < itemIds.size(); i++) {
            watchlistItemRepository.updateDisplayOrder(itemIds.get(i), i);
        }

        log.debug("Takip listesi hisse sirasi guncellendi: userId={}, watchlistId={}, itemCount={}",
                userId, watchlistId, itemIds.size());
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Watchlist entity'sini WatchlistDto'ya donusturur.
     *
     * @param w entity
     * @return DTO
     */
    private WatchlistDto toWatchlistDto(Watchlist w) {
        return WatchlistDto.builder()
                .id(w.getId())
                .name(w.getName())
                .description(w.getDescription())
                .displayOrder(w.getDisplayOrder())
                .isDefault(w.getIsDefault())
                .stockCount((int) watchlistItemRepository.countByWatchlistId(w.getId()))
                .createTime(w.getCreateTime())
                .updateTime(w.getUpdateTime())
                .build();
    }

    /**
     * WatchlistItem entity'sini WatchlistStockDto'ya donusturur.
     *
     * @param item entity
     * @return DTO
     */
    private WatchlistStockDto toStockDto(WatchlistItem item) {
        return WatchlistStockDto.builder()
                .id(item.getId())
                .stockCode(item.getStockCode())
                .stockName(item.getStockName())
                .displayOrder(item.getDisplayOrder())
                .build();
    }

    /**
     * WatchlistStockDto'yu anlik fiyat verileriyle zenginlestirir.
     *
     * <p>QuotePriceCache'ten {@code lp} (lastPrice), {@code ch} (change),
     * {@code chp} (changePercent) ve {@code volume} degerleri okunur.</p>
     *
     * @param dto zenginlestirilecek DTO
     * @return zenginlestirilmis DTO
     */
    private WatchlistStockDto enrichWithLivePrice(WatchlistStockDto dto) {
        if (quotePriceCache == null) {
            return dto;
        }
        try {
            Map<String, Object> quoteData = quotePriceCache.get(dto.getStockCode());
            if (quoteData != null) {
                Object lp = quoteData.get("lp");
                if (lp instanceof Number) {
                    dto.setLastPrice(((Number) lp).doubleValue());
                }
                Object ch = quoteData.get("ch");
                if (ch instanceof Number) {
                    dto.setChange(((Number) ch).doubleValue());
                }
                Object chp = quoteData.get("chp");
                if (chp instanceof Number) {
                    dto.setChangePercent(((Number) chp).doubleValue());
                }
                Object vol = quoteData.get("volume");
                if (vol instanceof Number) {
                    dto.setVolume(((Number) vol).doubleValue());
                }
            }
        } catch (Exception e) {
            log.debug("Fiyat bilgisi alinamadi: stockCode={}, hata={}", dto.getStockCode(), e.getMessage());
        }
        return dto;
    }

    /**
     * QuotePriceCache'ten fiyat alinamayan hisseler icin TradingView Scanner API fallback.
     *
     * <p>Seans kapali oldugunda QuotePriceCache bos olur ve tum fiyatlar {@code null} kalir.
     * Bu metot, fiyati olmayan hisseleri tespit edip tek bir TradingView Scanner batch scan ile
     * son kapanis fiyatlarini ceker.</p>
     *
     * @param stocks fiyat zenginlestirmesi yapilmis (veya yapilamamis) hisse listesi
     * @return Scanner fallback ile zenginlestirilmis hisse listesi
     */
    private List<WatchlistStockDto> enrichWithScannerFallback(List<WatchlistStockDto> stocks) {
        if (screenerClient == null || stocks.isEmpty()) {
            return stocks;
        }

        // Fiyati olmayan hisseleri filtrele
        List<WatchlistStockDto> unenriched = stocks.stream()
                .filter(s -> s.getLastPrice() == null)
                .toList();

        if (unenriched.isEmpty()) {
            return stocks;
        }

        try {
            // Ticker listesi olustur: "BIST:THYAO","BIST:GARAN"
            List<String> tickers = unenriched.stream()
                    .map(s -> "BIST:" + s.getStockCode())
                    .toList();

            String tickersJson = objectMapper.writeValueAsString(tickers);

            String scanBodyJson = """
                    {
                      "columns": ["close", "change", "change_abs", "logoid"],
                      "symbols": {"tickers": %s},
                      "options": {"lang": "tr"},
                      "range": [0, %d]
                    }
                    """.formatted(tickersJson, unenriched.size());

            ScanBodyDefinition scanBody = new ScanBodyDefinition("WATCHLIST_FALLBACK", scanBodyJson);
            TvScreenerResponse response = screenerClient.executeScan(scanBody);

            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                return stocks;
            }

            // Ticker -> DTO map olustur (hizli eslestirme icin)
            Map<String, WatchlistStockDto> stockMap = unenriched.stream()
                    .collect(Collectors.toMap(WatchlistStockDto::getStockCode, s -> s));

            for (TvScreenerResponse.DataItem item : response.getData()) {
                if (item.getS() == null || item.getD() == null) continue;

                String stockCode = item.getS().replace("BIST:", "");
                WatchlistStockDto dto = stockMap.get(stockCode);
                if (dto == null) continue;

                List<Object> d = item.getD();

                // d[0] = close (lastPrice)
                if (d.size() > 0 && d.get(0) instanceof Number) {
                    dto.setLastPrice(((Number) d.get(0)).doubleValue());
                }
                // d[1] = change (changePercent)
                if (d.size() > 1 && d.get(1) instanceof Number) {
                    dto.setChangePercent(((Number) d.get(1)).doubleValue());
                }
                // d[2] = change_abs (change absolute)
                if (d.size() > 2 && d.get(2) instanceof Number) {
                    dto.setChange(((Number) d.get(2)).doubleValue());
                }
                // d[3] = logoid
                if (d.size() > 3 && d.get(3) instanceof String) {
                    dto.setLogoid((String) d.get(3));
                }
            }

            log.info("[WATCHLIST] Scanner fallback: {} hisse icin fiyat cekildi", response.getData().size());

        } catch (Exception e) {
            log.warn("[WATCHLIST] Scanner fallback basarisiz: {}", e.getMessage());
        }

        return stocks;
    }

    /**
     * Takip listesinin belirtilen kullaniciya ait oldugunu dogrular.
     *
     * @param watchlist dogrulanacak liste
     * @param userId    beklenen kullanici ID'si
     * @throws RuntimeException sahiplik dogrulanmazsa
     */
    private void verifyOwnership(Watchlist watchlist, Long userId) {
        if (!watchlist.getUser().getId().equals(userId)) {
            throw new RuntimeException("Bu takip listesi size ait degil");
        }
    }

}
