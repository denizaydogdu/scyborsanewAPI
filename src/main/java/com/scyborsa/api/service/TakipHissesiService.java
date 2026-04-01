package com.scyborsa.api.service;

import com.scyborsa.api.dto.takiphissesi.TakipHissesiDto;
import com.scyborsa.api.dto.takiphissesi.TakipHissesiRequest;
import com.scyborsa.api.enums.YatirimVadesi;
import com.scyborsa.api.model.TakipHissesi;
import com.scyborsa.api.repository.TakipHissesiRepository;
import com.scyborsa.api.service.chart.QuotePriceCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

    /**
     * Aktif takip hisselerini guncel fiyat zenginlestirmesi ile getirir.
     *
     * @return aktif takip hissesi DTO listesi, siraNo'ya gore sirali
     */
    @Transactional(readOnly = true)
    public List<TakipHissesiDto> getAktifTakipHisseleri() {
        return takipHissesiRepository.findByAktifTrueOrderBySiraNoAsc()
                .stream()
                .map(this::toDto)
                .map(this::enrichWithCurrentPrice)
                .toList();
    }

    /**
     * Belirtilen vadedeki aktif takip hisselerini guncel fiyat zenginlestirmesi ile getirir.
     *
     * @param vade yatirim vadesi filtresi
     * @return filtrelenmis aktif takip hissesi DTO listesi, siraNo'ya gore sirali
     */
    @Transactional(readOnly = true)
    public List<TakipHissesiDto> getAktifTakipHisseleriByVade(YatirimVadesi vade) {
        return takipHissesiRepository.findByAktifTrueAndVadeOrderBySiraNoAsc(vade)
                .stream()
                .map(this::toDto)
                .map(this::enrichWithCurrentPrice)
                .toList();
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
        return takipHissesiRepository.findAll(Sort.by("siraNo"))
                .stream()
                .map(this::toDto)
                .map(this::enrichWithCurrentPrice)
                .toList();
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
        return takipHissesiRepository.findById(id)
                .map(this::toDto)
                .map(this::enrichWithCurrentPrice)
                .orElseThrow(() -> new IllegalArgumentException("Takip hissesi bulunamadı: id=" + id));
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

    // ==================== PRIVATE HELPERS ====================

    /**
     * DTO'yu guncel fiyat ve hesaplanmis alanlarla zenginlestirir.
     *
     * @param dto zenginlestirilecek DTO
     * @return zenginlestirilmis DTO
     */
    private TakipHissesiDto enrichWithCurrentPrice(TakipHissesiDto dto) {
        Double guncelFiyat = getCurrentPrice(dto.getHisseKodu());
        if (guncelFiyat != null) {
            dto.setGuncelFiyat(guncelFiyat);

            // Getiri yuzdesi hesapla
            if (dto.getGirisFiyati() != null && dto.getGirisFiyati() > 0) {
                double getiri = ((guncelFiyat - dto.getGirisFiyati()) / dto.getGirisFiyati()) * 100;
                dto.setGetiriYuzde(Math.round(getiri * 100.0) / 100.0);
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
        return dto;
    }

    /**
     * QuotePriceCache'ten anlik fiyat bilgisini ceker.
     *
     * @param hisseKodu hisse borsa kodu
     * @return anlik fiyat veya {@code null} (cache mevcut degilse veya veri yoksa)
     */
    private Double getCurrentPrice(String hisseKodu) {
        if (quotePriceCache == null) {
            return null;
        }
        try {
            Map<String, Object> quoteData = quotePriceCache.get("BIST:" + hisseKodu);
            if (quoteData != null && quoteData.containsKey("lp")) {
                Object lp = quoteData.get("lp");
                if (lp instanceof Number) {
                    return ((Number) lp).doubleValue();
                }
            }
        } catch (Exception e) {
            log.debug("[TAKIP-HISSESI] Fiyat bilgisi alinamadi: hisseKodu={}, hata={}", hisseKodu, e.getMessage());
        }
        return null;
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
                .notAciklama(request.getNotAciklama())
                .siraNo(request.getSiraNo())
                .build();
    }
}
