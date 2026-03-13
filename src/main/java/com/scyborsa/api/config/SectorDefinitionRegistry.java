package com.scyborsa.api.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.sector.SectorDefinitionDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * Sektor tanimi registry'si.
 *
 * <p>Uygulama basladiginda {@code sector-definitions.json} dosyasini yukleyerek
 * tum sektor tanimlarini bellekte tutar. Slug, legacy slug ve TradingView
 * sector/industry eslesmesi icin lookup metodlari sunar.</p>
 *
 * <p>Thread-safe: tum veri yapilari {@code @PostConstruct}'ta bir kez yuklenir
 * ve sonrasinda degistirilemez (unmodifiable).</p>
 *
 * @see SectorDefinitionDto
 * @see com.scyborsa.api.service.SectorService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SectorDefinitionRegistry {

    private final ObjectMapper objectMapper;

    /** Siralama numarasina gore sirali sektor tanim listesi. */
    private List<SectorDefinitionDto> definitions;

    /** Slug -> SectorDefinitionDto eslesmesi. */
    private Map<String, SectorDefinitionDto> slugMap;

    /** Legacy slug -> SectorDefinitionDto eslesmesi. */
    private Map<String, SectorDefinitionDto> legacySlugMap;

    /**
     * Uygulama basliginda {@code sector-definitions.json} dosyasini yukler.
     *
     * <p>JSON dosyasi okunamazsa bos listeler ile baslatilir ve
     * hata loglanir.</p>
     */
    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("sector-definitions.json");
            try (InputStream is = resource.getInputStream()) {
                List<SectorDefinitionDto> loaded = objectMapper.readValue(
                        is, new TypeReference<List<SectorDefinitionDto>>() {});

                Map<String, SectorDefinitionDto> slugs = new HashMap<>();
                Map<String, SectorDefinitionDto> legacies = new HashMap<>();

                for (SectorDefinitionDto def : loaded) {
                    slugs.put(def.getSlug(), def);
                    if (def.getLegacySlugs() != null) {
                        for (String legacy : def.getLegacySlugs()) {
                            legacies.put(legacy, def);
                        }
                    }
                }

                loaded.sort(Comparator.comparingInt(SectorDefinitionDto::getSiraNo));

                this.definitions = Collections.unmodifiableList(loaded);
                this.slugMap = Collections.unmodifiableMap(slugs);
                this.legacySlugMap = Collections.unmodifiableMap(legacies);

                log.info("[SECTOR-REGISTRY] {} sektor tanimi yuklendi", definitions.size());
            }
        } catch (Exception e) {
            log.error("[SECTOR-REGISTRY] sector-definitions.json yuklenemedi", e);
            this.definitions = List.of();
            this.slugMap = Map.of();
            this.legacySlugMap = Map.of();
        }
    }

    /**
     * Verilen slug veya legacy slug'a karsilik gelen sektor tanimini dondurur.
     *
     * <p>Once birincil slug map'inde aranir, bulunamazsa legacy slug map'ine bakilir.</p>
     *
     * @param slug sektor slug degeri (orn. "bankacilik" veya eski "banks")
     * @return eslesen {@link SectorDefinitionDto} veya bulunamazsa {@code null}
     */
    public SectorDefinitionDto getBySlug(String slug) {
        if (slug == null) return null;
        SectorDefinitionDto def = slugMap.get(slug);
        if (def == null) {
            def = legacySlugMap.get(slug);
        }
        return def;
    }

    /**
     * Verilen slug'i kanonik slug degerine cozumler.
     *
     * <p>Eger slug zaten birincil slug ise aynen dondurur.
     * Legacy slug ise kanonik slug dondurur. Hic bulunamazsa {@code null}.</p>
     *
     * @param slug kontrol edilecek slug degeri
     * @return kanonik slug degeri veya {@code null}
     */
    public String resolveSlug(String slug) {
        if (slug == null) return null;
        if (slugMap.containsKey(slug)) return slug;
        SectorDefinitionDto legacy = legacySlugMap.get(slug);
        return legacy != null ? legacy.getSlug() : null;
    }

    /**
     * Tum sektor tanimlarini siraNo'ya gore sirali olarak dondurur.
     *
     * @return degistirilemez sektor tanim listesi
     */
    public List<SectorDefinitionDto> getAll() {
        return definitions;
    }

    /**
     * Verilen TradingView sector, industry ve ticker bilgilerine gore
     * eslesen sektor tanimlarini dondurur.
     *
     * <p>Esleme mantigi:</p>
     * <ol>
     *   <li>Eger sektor tanimi ticker listesi iceriyorsa: ticker eslesmesi kontrol edilir</li>
     *   <li>Eger sektor tanimi tvSector iceriyorsa: sector eslesmesi kontrol edilir
     *       <ul>
     *         <li>tvIndustries null/bos ise: tum sector eslenir</li>
     *         <li>tvIndustries dolu ise: industry de eslesmelidir</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param stockTvSector  hissenin TradingView sector degeri
     * @param stockIndustry  hissenin TradingView industry degeri
     * @param ticker         hissenin borsa kodu (orn. "GARAN")
     * @return eslesen sektor tanim listesi (bos olabilir)
     */
    public List<SectorDefinitionDto> matchStock(String stockTvSector, String stockIndustry, String ticker) {
        List<SectorDefinitionDto> matches = new ArrayList<>();
        for (SectorDefinitionDto def : definitions) {
            if (def.getTickers() != null && !def.getTickers().isEmpty()) {
                // Ticker tabanli esleme
                if (ticker != null && def.getTickers().contains(ticker)) {
                    matches.add(def);
                }
            } else if (def.getTvSector() != null) {
                if (def.getTvSector().equals(stockTvSector)) {
                    if (def.getTvIndustries() == null || def.getTvIndustries().isEmpty()) {
                        // Tum sector eslesmesi (industry filtresi yok)
                        matches.add(def);
                    } else if (stockIndustry != null && def.getTvIndustries().contains(stockIndustry)) {
                        // Industry bazli esleme
                        matches.add(def);
                    }
                }
            }
        }
        return matches;
    }
}
