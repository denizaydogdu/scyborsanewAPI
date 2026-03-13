package com.scyborsa.api.service;

import com.scyborsa.api.dto.AraciKurumDto;
import com.scyborsa.api.dto.fintables.FintablesBrokerageDto;
import com.scyborsa.api.dto.analyst.AnalystRatingDto;
import com.scyborsa.api.model.AraciKurum;
import com.scyborsa.api.repository.AraciKurumRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Araci kurum is mantigi servisi.
 *
 * <p>Araci kurum CRUD islemleri ve Fintables analist tavsiyeleri
 * uzerinden otomatik sync islemlerini yonetir.</p>
 *
 * @see AraciKurum
 * @see AraciKurumDto
 * @see AraciKurumRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AraciKurumService {

    private final AraciKurumRepository araciKurumRepository;

    /**
     * Analist tavsiye verilerinden araci kurum bilgilerini sync eder.
     *
     * <p>Gelen tavsiyelerdeki brokerage bilgilerini extract edip
     * veritabanindaki araci kurum kayitlari ile eslestirir.
     * Yeni kurumlar olusturulur, mevcut kurumlarin title/shortTitle/logoUrl
     * alanlari guncellenir. {@code aktif} ve {@code siraNo} alanlari korunur.</p>
     *
     * @param ratings Fintables'dan gelen analist tavsiye listesi
     */
    @Transactional
    public void syncFromAnalystRatings(List<AnalystRatingDto> ratings) {
        if (ratings == null || ratings.isEmpty()) {
            log.debug("[ARACI-KURUM] Sync icin tavsiye listesi bos, atlanıyor");
            return;
        }

        // Benzersiz brokerage'lari code'a gore topla (son gelen kazanir)
        Map<String, AnalystRatingDto.BrokerageDto> uniqueBrokerages = new LinkedHashMap<>();
        for (AnalystRatingDto rating : ratings) {
            if (rating.getBrokerage() != null && rating.getBrokerage().getCode() != null) {
                uniqueBrokerages.put(rating.getBrokerage().getCode(), rating.getBrokerage());
            }
        }

        // Tum mevcut kurumlari tek sorguda getir (N+1 onleme)
        Map<String, AraciKurum> existingByCode = araciKurumRepository
                .findByCodeIn(uniqueBrokerages.keySet()).stream()
                .collect(Collectors.toMap(AraciKurum::getCode, Function.identity()));

        int newCount = 0;
        int updatedCount = 0;
        List<AraciKurum> toSave = new ArrayList<>();

        for (Map.Entry<String, AnalystRatingDto.BrokerageDto> entry : uniqueBrokerages.entrySet()) {
            String code = entry.getKey();
            AnalystRatingDto.BrokerageDto brokerage = entry.getValue();

            AraciKurum existing = existingByCode.get(code);
            if (existing != null) {
                // Sadece degisen alanlari guncelle (gereksiz UPDATE onleme)
                boolean dirty = !Objects.equals(existing.getTitle(), brokerage.getTitle())
                        || !Objects.equals(existing.getShortTitle(), brokerage.getShortTitle())
                        || !Objects.equals(existing.getLogoUrl(), brokerage.getLogo());
                if (dirty) {
                    existing.setTitle(brokerage.getTitle());
                    existing.setShortTitle(brokerage.getShortTitle());
                    existing.setLogoUrl(brokerage.getLogo());
                    // aktif ve siraNo korunur — kullanici tarafindan yonetilir
                    toSave.add(existing);
                    updatedCount++;
                }
            } else {
                AraciKurum yeniKurum = AraciKurum.builder()
                        .code(code)
                        .title(brokerage.getTitle())
                        .shortTitle(brokerage.getShortTitle())
                        .logoUrl(brokerage.getLogo())
                        .aktif(true)
                        .build();
                toSave.add(yeniKurum);
                newCount++;
            }
        }

        araciKurumRepository.saveAll(toSave);
        log.info("[ARACI-KURUM] Sync tamamlandi: {} yeni, {} guncellenen (toplam {} benzersiz kurum)",
                newCount, updatedCount, uniqueBrokerages.size());
    }

    /**
     * Fintables brokerage listesinden araci kurum bilgilerini sync eder.
     *
     * <p>Gelen brokerage listesindeki bilgileri veritabanindaki araci kurum
     * kayitlari ile eslestirir. Yeni kurumlar olusturulur, mevcut kurumlarin
     * title/shortTitle/logoUrl/publicCompany/isListed alanlari guncellenir.
     * {@code aktif} ve {@code siraNo} alanlari korunur — kullanici tarafindan yonetilir.</p>
     *
     * @param brokerages Fintables'dan gelen araci kurum listesi
     */
    @Transactional
    public void syncFromBrokerageList(List<FintablesBrokerageDto> brokerages) {
        if (brokerages == null || brokerages.isEmpty()) {
            log.debug("[ARACI-KURUM] Brokerage listesi bos, atlanıyor");
            return;
        }

        // Benzersiz brokerage'lari code'a gore topla (son gelen kazanir)
        Map<String, FintablesBrokerageDto> uniqueBrokerages = new LinkedHashMap<>();
        for (FintablesBrokerageDto b : brokerages) {
            if (b.getCode() != null && !b.getCode().isBlank()) {
                uniqueBrokerages.put(b.getCode(), b);
            }
        }

        // Tum mevcut kurumlari tek sorguda getir (N+1 onleme)
        Map<String, AraciKurum> existingByCode = araciKurumRepository
                .findByCodeIn(uniqueBrokerages.keySet()).stream()
                .collect(Collectors.toMap(AraciKurum::getCode, Function.identity()));

        int newCount = 0;
        int updatedCount = 0;
        List<AraciKurum> toSave = new ArrayList<>();

        for (Map.Entry<String, FintablesBrokerageDto> entry : uniqueBrokerages.entrySet()) {
            String code = entry.getKey();
            FintablesBrokerageDto brokerage = entry.getValue();

            AraciKurum existing = existingByCode.get(code);
            if (existing != null) {
                // Sadece degisen alanlari guncelle (gereksiz UPDATE onleme)
                boolean dirty = !Objects.equals(existing.getTitle(), brokerage.getTitle())
                        || !Objects.equals(existing.getShortTitle(), brokerage.getShortTitle())
                        || !Objects.equals(existing.getLogoUrl(), brokerage.getLogo())
                        || !Objects.equals(existing.getPublicCompany(), brokerage.getPublicCompany())
                        || !Objects.equals(existing.getIsListed(), brokerage.getIsListed());
                if (dirty) {
                    existing.setTitle(brokerage.getTitle());
                    existing.setShortTitle(brokerage.getShortTitle());
                    existing.setLogoUrl(brokerage.getLogo());
                    existing.setPublicCompany(brokerage.getPublicCompany());
                    existing.setIsListed(brokerage.getIsListed());
                    // aktif ve siraNo korunur — kullanici tarafindan yonetilir
                    toSave.add(existing);
                    updatedCount++;
                }
            } else {
                AraciKurum yeniKurum = AraciKurum.builder()
                        .code(code)
                        .title(brokerage.getTitle())
                        .shortTitle(brokerage.getShortTitle())
                        .logoUrl(brokerage.getLogo())
                        .publicCompany(brokerage.getPublicCompany())
                        .isListed(brokerage.getIsListed())
                        .aktif(true)
                        .build();
                toSave.add(yeniKurum);
                newCount++;
            }
        }

        araciKurumRepository.saveAll(toSave);
        log.info("[ARACI-KURUM] Brokerage sync: {} yeni, {} guncellenen (toplam {} benzersiz kurum)",
                newCount, updatedCount, uniqueBrokerages.size());
    }

    // --- CRUD metodlari (backoffice endpoint'leri eklendiginde kullanilacak) ---

    /**
     * Tum araci kurumlari basliga gore sirali dondurur.
     *
     * @return tum araci kurum DTO listesi
     */
    public List<AraciKurumDto> getTumAraciKurumlar() {
        return araciKurumRepository.findAllByOrderByTitleAsc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Aktif araci kurumlari sira numarasina gore sirali dondurur.
     *
     * @return aktif araci kurum DTO listesi
     */
    public List<AraciKurumDto> getAktifAraciKurumlar() {
        return araciKurumRepository.findByAktifTrueOrderBySiraNoAsc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Koda gore araci kurum getirir.
     *
     * @param code araci kurum kodu
     * @return araci kurum DTO
     * @throws IllegalArgumentException belirtilen kodda araci kurum bulunamazsa
     */
    public AraciKurumDto getAraciKurumByCode(String code) {
        AraciKurum kurum = araciKurumRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Araci kurum bulunamadi: " + code));
        return toDto(kurum);
    }

    /**
     * Yeni araci kurum olusturur.
     *
     * @param dto olusturulacak araci kurum bilgileri
     * @return olusturulan araci kurum DTO
     */
    @Transactional
    public AraciKurumDto createAraciKurum(AraciKurumDto dto) {
        if (araciKurumRepository.existsByCode(dto.getCode())) {
            throw new IllegalArgumentException("Bu kodla araci kurum zaten mevcut: " + dto.getCode());
        }
        AraciKurum kurum = AraciKurum.builder()
                .code(dto.getCode())
                .title(dto.getTitle())
                .shortTitle(dto.getShortTitle())
                .logoUrl(dto.getLogoUrl())
                .publicCompany(dto.getPublicCompany())
                .isListed(dto.getIsListed())
                .aktif(dto.getAktif() != null ? dto.getAktif() : true)
                .siraNo(dto.getSiraNo())
                .build();
        AraciKurum saved = araciKurumRepository.save(kurum);
        return toDto(saved);
    }

    /**
     * Mevcut araci kurumu gunceller.
     *
     * @param id guncellenecek araci kurum ID'si
     * @param dto yeni araci kurum bilgileri
     * @return guncellenmis araci kurum DTO
     * @throws IllegalArgumentException belirtilen ID'de araci kurum bulunamazsa
     */
    @Transactional
    public AraciKurumDto updateAraciKurum(Long id, AraciKurumDto dto) {
        AraciKurum kurum = araciKurumRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Araci kurum bulunamadi: id=" + id));
        // Farkli bir kuruma ait code ile cakisma kontrolu
        if (!kurum.getCode().equals(dto.getCode())
                && araciKurumRepository.existsByCode(dto.getCode())) {
            throw new IllegalArgumentException("Bu kodla araci kurum zaten mevcut: " + dto.getCode());
        }
        kurum.setCode(dto.getCode());
        kurum.setTitle(dto.getTitle());
        kurum.setShortTitle(dto.getShortTitle());
        kurum.setLogoUrl(dto.getLogoUrl());
        kurum.setPublicCompany(dto.getPublicCompany());
        kurum.setIsListed(dto.getIsListed());
        if (dto.getAktif() != null) kurum.setAktif(dto.getAktif());
        if (dto.getSiraNo() != null) kurum.setSiraNo(dto.getSiraNo());
        AraciKurum saved = araciKurumRepository.save(kurum);
        return toDto(saved);
    }

    /**
     * Araci kurumu soft delete yapar (aktif=false).
     *
     * @param id silinecek araci kurum ID'si
     * @throws IllegalArgumentException belirtilen ID'de araci kurum bulunamazsa
     */
    public void deleteAraciKurum(Long id) {
        setAktifDurumu(id, false, "Araci kurum soft delete");
    }

    /**
     * Pasif araci kurumu tekrar aktiflestirir.
     *
     * @param id aktiflestirilecek araci kurum ID'si
     * @throws IllegalArgumentException belirtilen ID'de araci kurum bulunamazsa
     */
    public void activateAraciKurum(Long id) {
        setAktifDurumu(id, true, "Araci kurum aktiflestirildi");
    }

    /**
     * Araci kurumun aktif durumunu degistirir.
     *
     * @param id araci kurum ID'si
     * @param aktif yeni aktif durumu
     * @param logMesaji log'a yazilacak islem aciklamasi
     * @throws IllegalArgumentException belirtilen ID'de araci kurum bulunamazsa
     */
    private void setAktifDurumu(Long id, boolean aktif, String logMesaji) {
        AraciKurum kurum = araciKurumRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Araci kurum bulunamadi: id=" + id));
        kurum.setAktif(aktif);
        araciKurumRepository.save(kurum);
        log.info("[ARACI-KURUM] {}: id={}, code={}", logMesaji, id, kurum.getCode());
    }

    /**
     * AraciKurum entity'sini AraciKurumDto'ya donusturur.
     *
     * @param entity kaynak entity
     * @return donusturulmus DTO
     */
    private AraciKurumDto toDto(AraciKurum entity) {
        return AraciKurumDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .title(entity.getTitle())
                .shortTitle(entity.getShortTitle())
                .logoUrl(entity.getLogoUrl())
                .publicCompany(entity.getPublicCompany())
                .isListed(entity.getIsListed())
                .aktif(entity.getAktif())
                .siraNo(entity.getSiraNo())
                .build();
    }
}
