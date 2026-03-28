package com.scyborsa.api.controller;

import com.scyborsa.api.dto.watchlist.AddStockRequest;
import com.scyborsa.api.dto.watchlist.CreateWatchlistRequest;
import com.scyborsa.api.dto.watchlist.ReorderRequest;
import com.scyborsa.api.dto.watchlist.WatchlistDto;
import com.scyborsa.api.dto.watchlist.WatchlistStockDto;
import com.scyborsa.api.repository.AppUserRepository;
import com.scyborsa.api.service.watchlist.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Takip listesi REST controller'i.
 *
 * <p>Kullanicilarin takip listesi olusturma, guncelleme, silme,
 * hisse ekleme/cikarma ve siralama endpoint'lerini saglar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/watchlists}</p>
 *
 * <p><b>Not:</b> userId simdilik request parametresi olarak alinir.
 * Ileride security context'ten cikarilacaktir.</p>
 *
 * @see WatchlistService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/watchlists")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final AppUserRepository appUserRepository;

    /**
     * Kullanicinin takip listelerini getirir.
     *
     * @param email kullanici email adresi
     * @return takip listesi DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<WatchlistDto>> getUserWatchlists(@RequestParam String email) {
        List<WatchlistDto> watchlists = watchlistService.getUserWatchlists(resolveUserId(email));
        return ResponseEntity.ok(watchlists);
    }

    /**
     * Yeni bir takip listesi olusturur.
     *
     * @param request liste olusturma istegi
     * @param email   kullanici email adresi
     * @return olusturulan takip listesi DTO'su
     */
    @PostMapping
    public ResponseEntity<WatchlistDto> createWatchlist(@Valid @RequestBody CreateWatchlistRequest request,
                                                        @RequestParam String email) {
        WatchlistDto created = watchlistService.createWatchlist(resolveUserId(email), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Mevcut bir takip listesini gunceller.
     *
     * @param id      takip listesi ID'si
     * @param request guncelleme istegi
     * @param email   kullanici email adresi
     * @return guncellenmis takip listesi DTO'su
     */
    @PutMapping("/{id}")
    public ResponseEntity<WatchlistDto> updateWatchlist(@PathVariable Long id,
                                                        @Valid @RequestBody CreateWatchlistRequest request,
                                                        @RequestParam String email) {
        WatchlistDto updated = watchlistService.updateWatchlist(resolveUserId(email), id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Belirtilen takip listesini siler (soft delete).
     *
     * @param id    takip listesi ID'si
     * @param email kullanici email adresi
     * @return 200 OK
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWatchlist(@PathVariable Long id, @RequestParam String email) {
        watchlistService.deleteWatchlist(resolveUserId(email), id);
        return ResponseEntity.ok().build();
    }

    /**
     * Belirtilen takip listesindeki hisseleri getirir.
     *
     * @param id    takip listesi ID'si
     * @param email kullanici email adresi
     * @return hisse DTO listesi
     */
    @GetMapping("/{id}/stocks")
    public ResponseEntity<List<WatchlistStockDto>> getWatchlistStocks(@PathVariable Long id,
                                                                      @RequestParam String email) {
        List<WatchlistStockDto> stocks = watchlistService.getWatchlistStocks(resolveUserId(email), id);
        return ResponseEntity.ok(stocks);
    }

    /**
     * Takip listesine yeni bir hisse ekler.
     *
     * @param id      takip listesi ID'si
     * @param request hisse ekleme istegi
     * @param email   kullanici email adresi
     * @return eklenen hisse DTO'su
     */
    @PostMapping("/{id}/stocks")
    public ResponseEntity<WatchlistStockDto> addStock(@PathVariable Long id,
                                                      @Valid @RequestBody AddStockRequest request,
                                                      @RequestParam String email) {
        WatchlistStockDto added = watchlistService.addStock(resolveUserId(email), id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(added);
    }

    /**
     * Takip listesinden belirtilen hisseyi cikarir.
     *
     * @param id        takip listesi ID'si
     * @param stockCode hisse borsa kodu
     * @param email     kullanici email adresi
     * @return 200 OK
     */
    @DeleteMapping("/{id}/stocks/{stockCode}")
    public ResponseEntity<Void> removeStock(@PathVariable Long id,
                                            @PathVariable String stockCode,
                                            @RequestParam String email) {
        watchlistService.removeStock(resolveUserId(email), id, stockCode);
        return ResponseEntity.ok().build();
    }

    /**
     * Takip listesindeki hisselerin siralamasini gunceller.
     *
     * @param id      takip listesi ID'si
     * @param request siralama degistirme istegi (yeni siraya gore item ID listesi)
     * @param email   kullanici email adresi
     * @return 200 OK
     */
    @PutMapping("/{id}/stocks/reorder")
    public ResponseEntity<Void> reorderStocks(@PathVariable Long id,
                                              @Valid @RequestBody ReorderRequest request,
                                              @RequestParam String email) {
        watchlistService.reorderStocks(resolveUserId(email), id, request);
        return ResponseEntity.ok().build();
    }

    /**
     * Kullanicinin varsayilan takip listesini getirir veya yoksa olusturur.
     *
     * @param email kullanici email adresi
     * @return varsayilan takip listesi DTO'su
     */
    @PostMapping("/default")
    public ResponseEntity<WatchlistDto> getOrCreateDefault(@RequestParam String email) {
        WatchlistDto defaultList = watchlistService.getOrCreateDefaultWatchlist(resolveUserId(email));
        return ResponseEntity.ok(defaultList);
    }

    /**
     * Email adresinden kullanici ID'sini cikarir.
     *
     * @param email kullanici email adresi
     * @return kullanici ID'si
     * @throws RuntimeException kullanici bulunamazsa veya email bos ise
     */
    private Long resolveUserId(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email parametresi zorunludur");
        }
        return appUserRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı"))
                .getId();
    }

    /**
     * RuntimeException'lari 400 Bad Request olarak doner.
     *
     * @param ex yakalanan calisma zamani hatasi
     * @return hata mesaji iceren JSON yanit
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        log.warn("[WATCHLIST] Hata: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
