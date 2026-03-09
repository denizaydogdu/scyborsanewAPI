package com.scyborsa.api.service;

import com.scyborsa.api.dto.AnalistDto;
import com.scyborsa.api.model.Analist;
import com.scyborsa.api.repository.AnalistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Analist is mantigi servisi.
 *
 * <p>Analist CRUD islemlerini ve entity-DTO donusumlerini yonetir.
 * Ilk calismada 8 analisti otomatik seed eder.</p>
 *
 * @see Analist
 * @see AnalistDto
 * @see AnalistRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalistService {

    private final AnalistRepository analistRepository;

    /**
     * Aktif analistleri sirali olarak getirir.
     *
     * @return aktif analist DTO listesi, siraNo'ya gore sirali
     */
    public List<AnalistDto> getAktifAnalistler() {
        return analistRepository.findByAktifTrueOrderBySiraNoAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Tum analistleri (aktif + pasif) sirali olarak getirir.
     *
     * @return tum analist DTO listesi, siraNo'ya gore sirali
     */
    public List<AnalistDto> getTumAnalistler() {
        return analistRepository.findAll(Sort.by("siraNo"))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * ID'ye gore analist getirir.
     *
     * @param id analist ID'si
     * @return analist DTO
     * @throws IllegalArgumentException analist bulunamazsa
     */
    public AnalistDto getAnalistById(Long id) {
        return analistRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Analist bulunamadi: id=" + id));
    }

    /**
     * Yeni analist olusturur.
     *
     * @param dto olusturulacak analist bilgileri
     * @return olusturulan analist DTO
     * @throws IllegalArgumentException ayni isimde analist varsa
     */
    public AnalistDto createAnalist(AnalistDto dto) {
        validateAd(dto.getAd());
        validateTrend(dto.getTrend());
        if (analistRepository.existsByAd(dto.getAd())) {
            throw new IllegalArgumentException("Bu isimde analist zaten var: " + dto.getAd());
        }
        Analist entity = toEntity(dto);
        entity.setAktif(true);
        Analist saved = analistRepository.save(entity);
        log.info("[ANALIST] Yeni analist olusturuldu: {}", saved.getAd());
        return toDto(saved);
    }

    /**
     * Mevcut analisti gunceller.
     *
     * @param id guncellenecek analist ID'si
     * @param dto yeni analist bilgileri
     * @return guncellenmis analist DTO
     * @throws IllegalArgumentException analist bulunamazsa veya isim cakismasi varsa
     */
    public AnalistDto updateAnalist(Long id, AnalistDto dto) {
        validateAd(dto.getAd());
        validateTrend(dto.getTrend());
        Analist entity = analistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Analist bulunamadi: id=" + id));
        if (!entity.getAd().equals(dto.getAd())
                && analistRepository.existsByAd(dto.getAd())) {
            throw new IllegalArgumentException("Bu isimde analist zaten var: " + dto.getAd());
        }
        entity.setAd(dto.getAd());
        entity.setUnvan(dto.getUnvan());
        entity.setResimUrl(dto.getResimUrl());
        entity.setHisseOnerisi(dto.getHisseOnerisi());
        entity.setKazanc(dto.getKazanc());
        entity.setTrend(dto.getTrend());
        entity.setChartRenk(dto.getChartRenk());
        entity.setChartVerisi(dto.getChartVerisi());
        entity.setSiraNo(dto.getSiraNo());
        Analist saved = analistRepository.save(entity);
        log.info("[ANALIST] Analist guncellendi: id={}, ad={}", id, saved.getAd());
        return toDto(saved);
    }

    /**
     * Analisti soft delete yapar (aktif=false).
     *
     * @param id silinecek analist ID'si
     * @throws IllegalArgumentException analist bulunamazsa
     */
    public void deleteAnalist(Long id) {
        Analist entity = analistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Analist bulunamadi: id=" + id));
        entity.setAktif(false);
        analistRepository.save(entity);
        log.info("[ANALIST] Analist pasife alindi: id={}, ad={}", id, entity.getAd());
    }

    /**
     * Pasif analisti tekrar aktif eder.
     *
     * @param id aktiflestirilecek analist ID'si
     * @throws IllegalArgumentException analist bulunamazsa
     */
    public void activateAnalist(Long id) {
        Analist entity = analistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Analist bulunamadi: id=" + id));
        entity.setAktif(true);
        analistRepository.save(entity);
        log.info("[ANALIST] Analist aktiflestirildi: id={}, ad={}", id, entity.getAd());
    }

    /**
     * Analist adinin null veya bos olmadigini dogrular.
     *
     * @param ad dogrulanacak analist adi
     * @throws IllegalArgumentException analist adi null veya bossa
     */
    private void validateAd(String ad) {
        if (ad == null || ad.isBlank()) {
            throw new IllegalArgumentException("Analist adi bos olamaz");
        }
    }

    /**
     * Trend alaninin null olmadigini dogrular.
     *
     * <p>Entity'de {@code trend} kolonu {@code nullable = false} oldugu icin
     * null deger DB constraint violation (500) yerine business validation (400) donmeli.</p>
     *
     * @param trend dogrulanacak trend degeri
     * @throws IllegalArgumentException trend null ise
     */
    private void validateTrend(Boolean trend) {
        if (trend == null) {
            throw new IllegalArgumentException("Trend alani bos olamaz");
        }
    }

    /**
     * Entity'yi DTO'ya donusturur.
     *
     * @param entity kaynak entity
     * @return donusturulmus DTO
     */
    private AnalistDto toDto(Analist entity) {
        return AnalistDto.builder()
                .id(entity.getId())
                .ad(entity.getAd())
                .unvan(entity.getUnvan())
                .resimUrl(entity.getResimUrl())
                .hisseOnerisi(entity.getHisseOnerisi())
                .kazanc(entity.getKazanc())
                .trend(entity.getTrend())
                .chartRenk(entity.getChartRenk())
                .chartVerisi(entity.getChartVerisi())
                .siraNo(entity.getSiraNo())
                .aktif(entity.getAktif())
                .build();
    }

    /**
     * DTO'yu entity'ye donusturur.
     *
     * @param dto kaynak DTO
     * @return donusturulmus entity
     */
    private Analist toEntity(AnalistDto dto) {
        return Analist.builder()
                .ad(dto.getAd())
                .unvan(dto.getUnvan())
                .resimUrl(dto.getResimUrl())
                .hisseOnerisi(dto.getHisseOnerisi())
                .kazanc(dto.getKazanc())
                .trend(dto.getTrend())
                .chartRenk(dto.getChartRenk())
                .chartVerisi(dto.getChartVerisi())
                .siraNo(dto.getSiraNo())
                .build();
    }

    /**
     * Uygulama tamamen ayaga kalktiktan sonra seed verilerini yukler.
     *
     * <p>{@code @EventListener(ApplicationReadyEvent.class)} kullanilir cunku
     * {@code @PostConstruct} ile {@code @Transactional} birlikte calismaz —
     * Spring AOP proxy'si henuz aktif degildir.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedInitialData() {
        long existingCount = analistRepository.count();
        if (existingCount > 0) {
            log.info("[ANALIST] Seed atlandi — tabloda {} kayit mevcut", existingCount);
            return;
        }

        Object[][] seedData = {
                {"Seyda Hoca", "Velzon", "/assets/images/companies/img-1.png", 452, 45415, true, "danger", "[12,14,2,47,42,15,47,75,65,19,14]", 1},
                {"Özkan Filiz", "Yatırım Enstitüsü", "/assets/images/companies/img-2.png", 784, 97642, false, "success", "[12,14,2,47,42,15,35,75,20,67,89]", 2},
                {"Tansu", "Profesör", "/assets/images/companies/img-3.png", 320, 27102, false, "warning", "[45,20,8,42,30,5,35,79,22,54,64]", 3},
                {"Çağlar Hoca", "Analist", "/assets/images/companies/img-4.png", 159, 14933, true, "success", "[26,15,48,12,47,19,35,19,85,68,50]", 4},
                {"Emrah Gülez", "Pine Script Üstadı", "/assets/images/companies/img-5.png", 363, 73426, false, "warning", "[60,67,12,49,6,78,63,51,33,8,16]", 5},
                {"Bay X", "Gizli Kahraman", "/assets/images/companies/img-6.png", 412, 34241, true, "success", "[78,63,51,33,8,16,60,67,12,49]", 6},
                {"Rıdvan Özturgut", "Akademisyen", "/assets/images/companies/img-7.png", 945, 17200, true, "danger", "[15,35,75,20,67,8,42,30,5,35]", 7},
                {"Kadir Koçak", "Borsa Yatırımcısı", "/assets/images/companies/img-8.png", 784, 97642, false, "warning", "[45,32,68,55,36,10,48,25,74,54]", 8}
        };

        List<Analist> toSave = new ArrayList<>();
        for (Object[] row : seedData) {
            toSave.add(Analist.builder()
                    .ad((String) row[0])
                    .unvan((String) row[1])
                    .resimUrl((String) row[2])
                    .hisseOnerisi((Integer) row[3])
                    .kazanc((Integer) row[4])
                    .trend((Boolean) row[5])
                    .chartRenk((String) row[6])
                    .chartVerisi((String) row[7])
                    .siraNo((Integer) row[8])
                    .aktif(true)
                    .build());
        }
        analistRepository.saveAll(toSave);
        log.info("[ANALIST] {} analist seed edildi", toSave.size());
    }
}
