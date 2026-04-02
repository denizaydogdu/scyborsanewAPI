package com.scyborsa.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.dto.takiphissesi.TakipHissesiDto;
import com.scyborsa.api.dto.takiphissesi.TakipHissesiRequest;
import com.scyborsa.api.enums.YatirimVadesi;
import com.scyborsa.api.model.TakipHissesi;
import com.scyborsa.api.repository.TakipHissesiRepository;
import com.scyborsa.api.service.chart.QuotePriceCache;
import com.scyborsa.api.service.market.Bist100Service;
import com.scyborsa.api.service.screener.TradingViewScreenerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Takip hissesi (hisse onerisi) is mantigi servisi.
 *
 * <p>Admin backoffice'den eklenen hisse onerilerinin CRUD islemlerini,
 * entity-DTO donusumlerini ve guncel fiyat zenginlestirmesini yonetir.</p>
 *
 * <p>{@link QuotePriceCache} opsiyonel olarak enjekte edilir; mevcut degilse
 * guncel fiyat zenginlestirmesi yapilmaz (graceful degradation).</p>
 *
 * @see TakipHissesi
 * @see TakipHissesiDto
 * @see TakipHissesiRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TakipHissesiService {

    /** Takip hissesi veritabani erisim katmani. */
    private final TakipHissesiRepository takipHissesiRepository;

    /** Anlik fiyat cache — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private QuotePriceCache quotePriceCache;

    /** BIST hisse logo haritasi — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private Bist100Service bist100Service;

    /** TradingView Scanner API client — QuotePriceCache bos oldugunda fallback olarak kullanilir. */
    @Autowired(required = false)
    private TradingViewScreenerClient screenerClient;

    /** JSON serializasyon icin ObjectMapper. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Aktif takip hisselerini guncel fiyat zenginlestirmesi ile getirir.
     *
     * @return aktif takip hissesi DTO listesi, siraNo'ya gore sirali
     */
    @Transactional(readOnly = true)
    public List<TakipHissesiDto> getAktifTakipHisseleri() {
        List<TakipHissesiDto> dtos = takipHissesiRepository.findByAktifTrueOrderBySiraNoAsc()
                .stream()
                .map(this::toDto)
                .toList();
        batchEnrichWithPrices(dtos);
        return dtos;
    }

    /**
     * Belirtilen vadedeki aktif takip hisselerini guncel fiyat zenginlestirmesi ile getirir.
     *
     * @param vade yatirim vadesi filtresi
     * @return filtrelenmis aktif takip hissesi DTO listesi, siraNo'ya gore sirali
     */
    @Transactional(readOnly = true)
    public List<TakipHissesiDto> getAktifTakipHisseleriByVade(YatirimVadesi vade) {
        List<TakipHissesiDto> dtos = takipHissesiRepository.findByAktifTrueAndVadeOrderBySiraNoAsc(vade)
                .stream()
                .map(this::toDto)
                .toList();
        batchEnrichWithPrices(dtos);
        return dtos;
    }

    /**
     * Tum takip hisselerini (aktif + pasif) guncel fiyat zenginlestirmesi ile getirir.
     *
     * <p>Backoffice yonetim paneli icin kullanilir.</p>
     *
     * @return tum takip hissesi DTO listesi, siraNo'ya gore sirali
     */
    @Transactional(readOnly = true)
    public List<TakipHissesiDto> getTumTakipHisseleri() {
        List<TakipHissesiDto> dtos = takipHissesiRepository.findAll(Sort.by("siraNo"))
                .stream()
                .map(this::toDto)
                .toList();
        batchEnrichWithPrices(dtos);
        return dtos;
    }

    /**
     * ID'ye gore takip hissesi getirir. Guncel fiyat zenginlestirmesi yapilir.
     *
     * @param id takip hissesi ID'si
     * @return takip hissesi DTO
     * @throws IllegalArgumentException kayit bulunamazsa
     */
    @Transactional(readOnly = true)
    public TakipHissesiDto getTakipHissesiById(Long id) {
        TakipHissesiDto dto = takipHissesiRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Takip hissesi bulunamadı: id=" + id));
        batchEnrichWithPrices(List.of(dto));
        return dto;
    }

    /**
     * Yeni takip hissesi onerisi olusturur.
     *
     * <p>Ayni hisse kodu ve vadede aktif bir kayit varsa duplikat hatasi verir.</p>
     *
     * @param request olusturma istegi
     * @return olusturulan takip hissesi DTO
     * @throws IllegalArgumentException hisse kodu bos veya duplikat kayit varsa
     */
    @Transactional
    public TakipHissesiDto createTakipHissesi(TakipHissesiRequest request) {
        // Validasyon
        if (request.getHisseKodu() == null || request.getHisseKodu().isBlank()) {
            throw new IllegalArgumentException("Hisse kodu boş olamaz");
        }

        String hisseKodu = request.getHisseKodu().toUpperCase().trim();

        // Duplikat kontrol
        if (takipHissesiRepository.existsByHisseKoduAndVadeAndAktifTrue(hisseKodu, request.getVade())) {
            throw new IllegalArgumentException(
                    "Bu hisse için aynı vadede aktif bir öneri zaten mevcut: " + hisseKodu + " / " + request.getVade());
        }

        TakipHissesi entity = toEntity(request);
        entity.setHisseKodu(hisseKodu);
        entity.setAktif(true);

        TakipHissesi saved = takipHissesiRepository.save(entity);
        log.info("[TAKIP-HISSESI] Yeni oneri olusturuldu: hisseKodu={}, vade={}", saved.getHisseKodu(), saved.getVade());
        return toDto(saved);
    }

    /**
     * Mevcut takip hissesi onerisini gunceller.
     *
     * @param id      guncellenecek kayit ID'si
     * @param request guncelleme istegi
     * @return guncellenmis takip hissesi DTO
     * @throws IllegalArgumentException kayit bulunamazsa veya duplikat olusursa
     */
    @Transactional
    public TakipHissesiDto updateTakipHissesi(Long id, TakipHissesiRequest request) {
        // Validasyon
        if (request.getHisseKodu() == null || request.getHisseKodu().isBlank()) {
            throw new IllegalArgumentException("Hisse kodu boş olamaz");
        }

        TakipHissesi entity = takipHissesiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Takip hissesi bulunamadı: id=" + id));

        String hisseKodu = request.getHisseKodu().toUpperCase().trim();

        // Hisse kodu veya vade degistiyse duplikat kontrol
        if (!entity.getHisseKodu().equals(hisseKodu) || entity.getVade() != request.getVade()) {
            if (takipHissesiRepository.existsByHisseKoduAndVadeAndAktifTrue(hisseKodu, request.getVade())) {
                throw new IllegalArgumentException(
                        "Bu hisse için aynı vadede aktif bir öneri zaten mevcut: " + hisseKodu + " / " + request.getVade());
            }
        }

        entity.setHisseKodu(hisseKodu);
        entity.setHisseAdi(request.getHisseAdi());
        entity.setVade(request.getVade());
        entity.setGirisFiyati(request.getGirisFiyati());
        entity.setGirisTarihi(request.getGirisTarihi());
        entity.setHedefFiyat(request.getHedefFiyat());
        entity.setZararDurdur(request.getZararDurdur());
        entity.setNotAciklama(request.getNotAciklama());
        entity.setMaliyetFiyati(request.getMaliyetFiyati());
        entity.setSiraNo(request.getSiraNo());

        TakipHissesi saved = takipHissesiRepository.save(entity);
        log.info("[TAKIP-HISSESI] Oneri guncellendi: id={}, hisseKodu={}", id, saved.getHisseKodu());
        return toDto(saved);
    }

    /**
     * Takip hissesi onerisini soft delete yapar (aktif=false).
     *
     * @param id silinecek kayit ID'si
     * @throws IllegalArgumentException kayit bulunamazsa
     */
    @Transactional
    public void deleteTakipHissesi(Long id) {
        TakipHissesi entity = takipHissesiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Takip hissesi bulunamadı: id=" + id));
        entity.setAktif(false);
        takipHissesiRepository.save(entity);
        log.info("[TAKIP-HISSESI] Oneri pasife alindi: id={}, hisseKodu={}", id, entity.getHisseKodu());
    }

    /**
     * Pasif takip hissesi onerisini tekrar aktif eder.
     *
     * @param id aktiflestirilecek kayit ID'si
     * @throws IllegalArgumentException kayit bulunamazsa
     */
    @Transactional
    public void activateTakipHissesi(Long id) {
        TakipHissesi entity = takipHissesiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Takip hissesi bulunamadı: id=" + id));

        // Aynı hisse+vade için aktif kayıt var mı kontrol et
        if (takipHissesiRepository.existsByHisseKoduAndVadeAndAktifTrue(entity.getHisseKodu(), entity.getVade())) {
            throw new IllegalArgumentException(
                    "Bu hisse için aynı vadede aktif bir öneri zaten mevcut: "
                    + entity.getHisseKodu() + " / " + entity.getVade());
        }

        entity.setAktif(true);
        takipHissesiRepository.save(entity);
        log.info("[TAKIP-HISSESI] Öneri aktifleştirildi: id={}, hisseKodu={}", id, entity.getHisseKodu());
    }

    /**
     * Takip hissesinin resim URL'sini günceller.
     *
     * @param id       takip hissesi ID'si
     * @param resimUrl yeni resim dosya adı
     * @return güncellenmiş takip hissesi DTO
     * @throws IllegalArgumentException kayıt bulunamazsa
     */
    @Transactional
    public TakipHissesiDto updateResimUrl(Long id, String resimUrl) {
        TakipHissesi entity = takipHissesiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Takip hissesi bulunamadı: id=" + id));
        entity.setResimUrl(resimUrl);
        TakipHissesi saved = takipHissesiRepository.save(entity);
        log.info("[TAKIP-HISSESI] Resim güncellendi: id={}, resimUrl={}", id, resimUrl);
        return toDto(saved);
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * DTO listesini toplu olarak guncel fiyat ile zenginlestirir.
     *
     * <p>Iki asamali strateji kullanir:
     * <ol>
     *   <li><strong>QuotePriceCache:</strong> Anlik WS fiyat cache'inden okuma (maliyet sifir)</li>
     *   <li><strong>TradingView Scanner fallback:</strong> Cache'te bulunmayan hisseler icin
     *       tek bir toplu Scanner API cagrisi yapar</li>
     * </ol>
     * Takip hissesi stoklari WebSocket'e abone olmadigi icin QuotePriceCache genellikle bos olur;
     * Scanner fallback bu durumda devreye girer.</p>
     *
     * @param dtos zenginlestirilecek DTO listesi
     */
    private void batchEnrichWithPrices(List<TakipHissesiDto> dtos) {
        if (dtos.isEmpty()) {
            return;
        }

        // Logo haritasini bir kez al
        Map<String, String> logoMap = null;
        if (bist100Service != null) {
            try {
                logoMap = bist100Service.getStockLogoidMap();
            } catch (Exception e) {
                log.debug("[TAKIP-HISSESI] Logo haritasi alinamadi: {}", e.getMessage());
            }
        }

        // Adim 1: QuotePriceCache'ten fiyat okuma denemesi
        Map<String, Double> priceMap = new HashMap<>();
        List<TakipHissesiDto> needsScanner = new ArrayList<>();

        for (TakipHissesiDto dto : dtos) {
            // Logoid zenginlestirme
            if (logoMap != null && dto.getHisseKodu() != null) {
                dto.setLogoid(logoMap.get(dto.getHisseKodu()));
            }

            Double cachedPrice = getCurrentPriceFromCache(dto.getHisseKodu());
            if (cachedPrice != null) {
                priceMap.put(dto.getHisseKodu(), cachedPrice);
            } else {
                needsScanner.add(dto);
            }
        }

        // Adim 2: Cache'te bulunmayanlar icin Scanner fallback
        if (!needsScanner.isEmpty() && screenerClient != null) {
            List<String> missingCodes = needsScanner.stream()
                    .map(TakipHissesiDto::getHisseKodu)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .collect(Collectors.toList());

            if (!missingCodes.isEmpty()) {
                Map<String, Double> scannerPrices = fetchPricesFromScanner(missingCodes);
                priceMap.putAll(scannerPrices);

                if (!scannerPrices.isEmpty()) {
                    log.debug("[TAKIP-HISSESI] Scanner fallback: {} hisse icin fiyat alindi (toplam {} eksikti)",
                            scannerPrices.size(), missingCodes.size());
                }
            }
        }

        // Adim 3: Tum DTO'lari zenginlestir
        for (TakipHissesiDto dto : dtos) {
            Double guncelFiyat = priceMap.get(dto.getHisseKodu());
            if (guncelFiyat != null) {
                applyPriceEnrichment(dto, guncelFiyat);
            }
        }
    }

    /**
     * DTO'ya guncel fiyat ve hesaplanmis alanlari uygular.
     *
     * @param dto         zenginlestirilecek DTO
     * @param guncelFiyat guncel hisse fiyati
     */
    private void applyPriceEnrichment(TakipHissesiDto dto, double guncelFiyat) {
        dto.setGuncelFiyat(guncelFiyat);

        // Getiri yuzdesi hesapla
        if (dto.getGirisFiyati() != null && dto.getGirisFiyati() > 0) {
            double getiri = ((guncelFiyat - dto.getGirisFiyati()) / dto.getGirisFiyati()) * 100;
            dto.setGetiriYuzde(Math.round(getiri * 100.0) / 100.0);
        }

        // Maliyet bazli getiri yuzdesi
        if (dto.getMaliyetFiyati() != null && dto.getMaliyetFiyati() > 0) {
            double maliyetGetiri = ((guncelFiyat - dto.getMaliyetFiyati()) / dto.getMaliyetFiyati()) * 100;
            dto.setMaliyetGetiriYuzde(Math.round(maliyetGetiri * 100.0) / 100.0);
        }

        // Hedef fiyata ulasildi mi
        if (dto.getHedefFiyat() != null) {
            dto.setHedefUlasildi(guncelFiyat >= dto.getHedefFiyat());
        }

        // Zarar durdur seviyesine ulasildi mi
        if (dto.getZararDurdur() != null) {
            dto.setZararDurdurUlasildi(guncelFiyat <= dto.getZararDurdur());
        }
    }

    /**
     * QuotePriceCache'ten anlik fiyat bilgisini ceker.
     *
     * @param hisseKodu hisse borsa kodu
     * @return anlik fiyat veya {@code null} (cache mevcut degilse veya veri yoksa)
     */
    private Double getCurrentPriceFromCache(String hisseKodu) {
        if (quotePriceCache == null || hisseKodu == null) {
            return null;
        }
        try {
            Map<String, Object> quoteData = quotePriceCache.get(hisseKodu);
            if (quoteData != null && quoteData.containsKey("lp")) {
                Object lp = quoteData.get("lp");
                if (lp instanceof Number) {
                    return ((Number) lp).doubleValue();
                }
            }
        } catch (Exception e) {
            log.debug("[TAKIP-HISSESI] Cache fiyat okunamadi: hisseKodu={}, hata={}", hisseKodu, e.getMessage());
        }
        return null;
    }

    /**
     * TradingView Scanner API'den toplu fiyat bilgisi ceker.
     *
     * <p>Tek bir HTTP cagrisi ile tum hisselerin close fiyatini alir.
     * PriceAlertScanJob ile ayni scan body formatini kullanir.</p>
     *
     * @param stockCodes fiyati cekilecek hisse kodlari
     * @return hisse kodu → close fiyat eslesmesi; hata durumunda bos map
     */
    private Map<String, Double> fetchPricesFromScanner(List<String> stockCodes) {
        Map<String, Double> result = new HashMap<>();
        try {
            var tickers = stockCodes.stream()
                    .map(code -> "BIST:" + code)
                    .collect(Collectors.toList());
            var body = Map.of(
                    "symbols", Map.of("tickers", tickers),
                    "columns", List.of("close")
            );
            String scanBody = objectMapper.writeValueAsString(body);
            ScanBodyDefinition scanDef = new ScanBodyDefinition("TAKIP_HISSESI_PRICE", scanBody);

            TvScreenerResponse response = screenerClient.executeScan(scanDef);
            if (response == null || response.getData() == null) {
                log.debug("[TAKIP-HISSESI] Scanner API yanit vermedi");
                return result;
            }

            for (TvScreenerResponse.DataItem item : response.getData()) {
                if (item.getS() == null || item.getD() == null || item.getD().isEmpty()) {
                    continue;
                }

                // s = "BIST:THYAO" → "THYAO"
                String symbol = item.getS();
                if (symbol.contains(":")) {
                    symbol = symbol.substring(symbol.indexOf(':') + 1);
                }

                // d[0] = close price
                Object closeObj = item.getD().get(0);
                if (closeObj instanceof Number) {
                    double price = ((Number) closeObj).doubleValue();
                    if (price > 0) {
                        result.put(symbol, price);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[TAKIP-HISSESI] Scanner fiyat cekme hatasi: {}", e.getMessage());
        }
        return result;
    }

    /**
     * TakipHissesi entity'sini TakipHissesiDto'ya donusturur.
     *
     * @param entity kaynak entity
     * @return donusturulmus DTO
     */
    private TakipHissesiDto toDto(TakipHissesi entity) {
        return TakipHissesiDto.builder()
                .id(entity.getId())
                .hisseKodu(entity.getHisseKodu())
                .hisseAdi(entity.getHisseAdi())
                .vade(entity.getVade())
                .girisFiyati(entity.getGirisFiyati())
                .girisTarihi(entity.getGirisTarihi())
                .hedefFiyat(entity.getHedefFiyat())
                .zararDurdur(entity.getZararDurdur())
                .maliyetFiyati(entity.getMaliyetFiyati())
                .resimUrl(entity.getResimUrl())
                .notAciklama(entity.getNotAciklama())
                .aktif(entity.getAktif())
                .siraNo(entity.getSiraNo())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    /**
     * TakipHissesiRequest'i TakipHissesi entity'sine donusturur.
     *
     * @param request kaynak istek DTO
     * @return donusturulmus entity
     */
    private TakipHissesi toEntity(TakipHissesiRequest request) {
        return TakipHissesi.builder()
                .hisseKodu(request.getHisseKodu().toUpperCase().trim())
                .hisseAdi(request.getHisseAdi())
                .vade(request.getVade())
                .girisFiyati(request.getGirisFiyati())
                .girisTarihi(request.getGirisTarihi())
                .hedefFiyat(request.getHedefFiyat())
                .zararDurdur(request.getZararDurdur())
                .maliyetFiyati(request.getMaliyetFiyati())
                .notAciklama(request.getNotAciklama())
                .siraNo(request.getSiraNo())
                .build();
    }
}
