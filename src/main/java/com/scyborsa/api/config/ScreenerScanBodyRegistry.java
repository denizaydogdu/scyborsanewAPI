package com.scyborsa.api.config;

import com.scyborsa.api.dto.ScanBodyDefinition;
import com.scyborsa.api.enums.ScreenerTypeEnum;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Tarama gövdesi (scan body) JSON dosyalarını yükleyip sunan registry.
 *
 * <p>{@code resources/screener-bodies/<screenerType.resourcePath>/*.json}
 * dizinlerindeki dosyaları {@code @PostConstruct} aşamasında yükler ve
 * {@link ScreenerTypeEnum} bazında gruplar.</p>
 *
 * @see ScanBodyDefinition
 * @see ScreenerTypeEnum
 */
@Slf4j
@Component
public class ScreenerScanBodyRegistry {

    private static final String BASE_PATH = "classpath*:screener-bodies/";

    private Map<ScreenerTypeEnum, List<ScanBodyDefinition>> registry;

    /**
     * Uygulama başlangıcında tüm screener türleri için JSON dosyalarını yükler.
     *
     * @throws IllegalStateException hiçbir scan body yüklenemezse
     */
    @PostConstruct
    public void init() {
        var resolver = new PathMatchingResourcePatternResolver();
        var mutable = new EnumMap<ScreenerTypeEnum, List<ScanBodyDefinition>>(ScreenerTypeEnum.class);
        int totalLoaded = 0;

        for (ScreenerTypeEnum type : ScreenerTypeEnum.values()) {
            String pattern = BASE_PATH + type.getResourcePath() + "/*.json";
            try {
                Resource[] resources = resolver.getResources(pattern);
                List<ScanBodyDefinition> bodies = new ArrayList<>();

                for (Resource resource : resources) {
                    String filename = resource.getFilename();
                    if (filename == null) continue;

                    String name = filename.replace(".json", "");
                    try (InputStream is = resource.getInputStream()) {
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        bodies.add(new ScanBodyDefinition(name, body));
                    } catch (IOException e) {
                        log.warn("[SCAN-BODY-REGISTRY] {} dosyası yüklenemedi: {}", filename, e.getMessage());
                    }
                }

                mutable.put(type, Collections.unmodifiableList(bodies));
                totalLoaded += bodies.size();
                log.info("[SCAN-BODY-REGISTRY] {} → {} scan body yüklendi", type.getCode(), bodies.size());
            } catch (IOException e) {
                log.error("[SCAN-BODY-REGISTRY] {} için dizin okunamadı: {}", type.getCode(), e.getMessage());
                mutable.put(type, List.of());
            }
        }

        this.registry = Collections.unmodifiableMap(mutable);
        log.info("[SCAN-BODY-REGISTRY] Toplam {} scan body, {} tür yüklendi", totalLoaded, registry.size());

        if (totalLoaded == 0) {
            throw new IllegalStateException(
                    "[SCAN-BODY-REGISTRY] Hiçbir scan body yüklenemedi! screener-bodies/ dizinini kontrol edin.");
        }
    }

    /**
     * Belirtilen tarama türünün scan body tanımlarını döndürür.
     *
     * @param type tarama türü
     * @return scan body listesi; tür bulunamazsa boş liste
     */
    public List<ScanBodyDefinition> getScanBodies(ScreenerTypeEnum type) {
        return registry.getOrDefault(type, List.of());
    }

    /**
     * Yüklenen toplam scan body sayısını döndürür.
     *
     * @return toplam scan body adedi
     */
    public int getTotalCount() {
        return registry.values().stream().mapToInt(List::size).sum();
    }
}
