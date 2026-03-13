package com.scyborsa.api.service;

import com.scyborsa.api.dto.ModelPortfoyKurumDto;
import com.scyborsa.api.model.ModelPortfoyKurum;
import com.scyborsa.api.repository.ModelPortfoyKurumRepository;
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
 * Model portföy iş mantığı servisi.
 *
 * <p>Aracı kurum CRUD işlemlerini ve entity-DTO dönüşümlerini yönetir.
 * İlk çalışmada 15 aracı kurumu otomatik seed eder.</p>
 *
 * @see ModelPortfoyKurum
 * @see ModelPortfoyKurumDto
 * @see ModelPortfoyKurumRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelPortfoyService {

    /** Varsayilan araci kurum logo URL'i. */
    private static final String DEFAULT_LOGO = "/assets/images/brokers/default-broker.png";

    /** Model portfoy kurum veritabani erisim katmani. */
    private final ModelPortfoyKurumRepository kurumRepository;

    /**
     * Aktif kurumları sıralı olarak getirir.
     *
     * @return aktif kurum DTO listesi, siraNo'ya göre sıralı
     */
    public List<ModelPortfoyKurumDto> getAktifKurumlar() {
        return kurumRepository.findByAktifTrueOrderBySiraNoAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Tum kurumlari (aktif + pasif) sirali olarak getirir.
     *
     * @return tum kurum DTO listesi, siraNo'ya gore sirali
     */
    public List<ModelPortfoyKurumDto> getTumKurumlar() {
        return kurumRepository.findAll(Sort.by("siraNo"))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * ID'ye göre kurum getirir.
     *
     * @param id kurum ID'si
     * @return kurum DTO
     * @throws IllegalArgumentException kurum bulunamazsa
     */
    public ModelPortfoyKurumDto getKurumById(Long id) {
        return kurumRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Kurum bulunamadı: id=" + id));
    }

    /**
     * Yeni kurum oluşturur.
     *
     * @param dto oluşturulacak kurum bilgileri
     * @return oluşturulan kurum DTO
     * @throws IllegalArgumentException aynı isimde kurum varsa
     */
    public ModelPortfoyKurumDto createKurum(ModelPortfoyKurumDto dto) {
        validateKurumAdi(dto.getKurumAdi());
        if (kurumRepository.existsByKurumAdi(dto.getKurumAdi())) {
            throw new IllegalArgumentException("Bu isimde kurum zaten var: " + dto.getKurumAdi());
        }
        ModelPortfoyKurum entity = toEntity(dto);
        entity.setAktif(true);
        ModelPortfoyKurum saved = kurumRepository.save(entity);
        log.info("[MODEL-PORTFOY] Yeni kurum oluşturuldu: {}", saved.getKurumAdi());
        return toDto(saved);
    }

    /**
     * Mevcut kurumu günceller.
     *
     * @param id güncellenecek kurum ID'si
     * @param dto yeni kurum bilgileri
     * @return güncellenmiş kurum DTO
     * @throws IllegalArgumentException kurum bulunamazsa
     */
    public ModelPortfoyKurumDto updateKurum(Long id, ModelPortfoyKurumDto dto) {
        validateKurumAdi(dto.getKurumAdi());
        ModelPortfoyKurum entity = kurumRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Kurum bulunamadı: id=" + id));
        if (!entity.getKurumAdi().equals(dto.getKurumAdi())
                && kurumRepository.existsByKurumAdi(dto.getKurumAdi())) {
            throw new IllegalArgumentException("Bu isimde kurum zaten var: " + dto.getKurumAdi());
        }
        entity.setKurumAdi(dto.getKurumAdi());
        entity.setLogoUrl(dto.getLogoUrl());
        entity.setHisseSayisi(dto.getHisseSayisi());
        entity.setSiraNo(dto.getSiraNo());
        ModelPortfoyKurum saved = kurumRepository.save(entity);
        log.info("[MODEL-PORTFOY] Kurum güncellendi: id={}, ad={}", id, saved.getKurumAdi());
        return toDto(saved);
    }

    /**
     * Kurumu soft delete yapar (aktif=false).
     *
     * @param id silinecek kurum ID'si
     * @throws IllegalArgumentException kurum bulunamazsa
     */
    public void deleteKurum(Long id) {
        ModelPortfoyKurum entity = kurumRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Kurum bulunamadı: id=" + id));
        entity.setAktif(false);
        kurumRepository.save(entity);
        log.info("[MODEL-PORTFOY] Kurum pasife alındı: id={}, ad={}", id, entity.getKurumAdi());
    }

    /**
     * Pasif kurumu tekrar aktif eder.
     *
     * @param id aktifleştirilecek kurum ID'si
     * @throws IllegalArgumentException kurum bulunamazsa
     */
    public void activateKurum(Long id) {
        ModelPortfoyKurum entity = kurumRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Kurum bulunamadı: id=" + id));
        entity.setAktif(true);
        kurumRepository.save(entity);
        log.info("[MODEL-PORTFOY] Kurum aktifleştirildi: id={}, ad={}", id, entity.getKurumAdi());
    }

    /**
     * Kurum adının null veya boş olmadığını doğrular.
     *
     * @param kurumAdi doğrulanacak kurum adı
     * @throws IllegalArgumentException kurum adı null veya boşsa
     */
    private void validateKurumAdi(String kurumAdi) {
        if (kurumAdi == null || kurumAdi.isBlank()) {
            throw new IllegalArgumentException("Kurum adı boş olamaz");
        }
    }

    /**
     * Entity'yi DTO'ya dönüştürür.
     *
     * @param entity kaynak entity
     * @return dönüştürülmüş DTO
     */
    private ModelPortfoyKurumDto toDto(ModelPortfoyKurum entity) {
        return ModelPortfoyKurumDto.builder()
                .id(entity.getId())
                .kurumAdi(entity.getKurumAdi())
                .logoUrl(entity.getLogoUrl())
                .hisseSayisi(entity.getHisseSayisi())
                .siraNo(entity.getSiraNo())
                .aktif(entity.getAktif())
                .build();
    }

    /**
     * DTO'yu entity'ye dönüştürür.
     *
     * @param dto kaynak DTO
     * @return dönüştürülmüş entity
     */
    private ModelPortfoyKurum toEntity(ModelPortfoyKurumDto dto) {
        return ModelPortfoyKurum.builder()
                .kurumAdi(dto.getKurumAdi())
                .logoUrl(dto.getLogoUrl())
                .hisseSayisi(dto.getHisseSayisi())
                .siraNo(dto.getSiraNo())
                .build();
    }

    /**
     * Uygulama tamamen ayağa kalktıktan sonra seed verilerini yükler.
     *
     * <p>{@code @EventListener(ApplicationReadyEvent.class)} kullanılır çünkü
     * {@code @PostConstruct} ile {@code @Transactional} birlikte çalışmaz —
     * Spring AOP proxy'si henüz aktif değildir.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedInitialData() {
        long existingCount = kurumRepository.count();
        if (existingCount > 0) {
            log.info("[MODEL-PORTFOY] Seed atlandı — tabloda {} kayıt mevcut", existingCount);
            return;
        }

        String[][] seedData = {
                {"Burgan", "4"},
                {"Tacirler", "3"},
                {"Ata Yatırım", "3"},
                {"Kuveyt Türk", "9"},
                {"İntegral Yatırım", "7"},
                {"Yapı Kredi", "12"},
                {"A1 Cap", "2"},
                {"İş", "5"},
                {"ALB Yatırım", "4"},
                {"Gedik Yatırım", "6"},
                {"Ahlatcı", "4"},
                {"Oyak Yatırım", "13"},
                {"Anadolu Yatırım", "5"},
                {"Teb Yatırım", "3"},
                {"Ziraat Yatırım", "3"}
        };

        List<ModelPortfoyKurum> toSave = new ArrayList<>();
        for (int i = 0; i < seedData.length; i++) {
            toSave.add(ModelPortfoyKurum.builder()
                    .kurumAdi(seedData[i][0])
                    .hisseSayisi(Integer.parseInt(seedData[i][1]))
                    .logoUrl(DEFAULT_LOGO)
                    .aktif(true)
                    .siraNo(i + 1)
                    .build());
        }
        kurumRepository.saveAll(toSave);
        log.info("[MODEL-PORTFOY] {} aracı kurum seed edildi", toSave.size());
    }
}
