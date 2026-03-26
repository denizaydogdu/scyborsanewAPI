package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * BIST Katilim Endeksi uyelik kontrolu saglayan servis.
 *
 * <p>Uygulama baslatildiginda {@code katilim-endeksi.json} dosyasindan
 * katilim endeksine dahil hisse kodlarini yukler ve bellekte tutar.
 * Diger servisler bu servisi kullanarak bir hissenin katilim endeksinde
 * olup olmadigini sorgulayabilir.</p>
 *
 * @see #isKatilim(String)
 * @see #getKatilimCodes()
 */
@Slf4j
@Service
public class KatilimEndeksiService {

    /** Katilim endeksi hisse kodlari (immutable). */
    private volatile Set<String> katilimCodes = Set.of();

    /**
     * Uygulama baslatildiginda {@code katilim-endeksi.json} dosyasini okur
     * ve katilim kodlarini bellek icerisine yukler.
     */
    @PostConstruct
    public void init() {
        try (InputStream is = getClass().getResourceAsStream("/katilim-endeksi.json")) {
            if (is == null) {
                log.error("katilim-endeksi.json dosyasi bulunamadi");
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            List<String> codes = mapper.readValue(is, new TypeReference<List<String>>() {});
            katilimCodes = Set.copyOf(codes);
            log.info("Katilim Endeksi: {} hisse yuklendi", katilimCodes.size());
        } catch (Exception e) {
            throw new IllegalStateException("Katilim endeksi JSON yuklenemedi", e);
        }
    }

    /**
     * Belirtilen hisse kodunun katilim endeksinde olup olmadigini kontrol eder.
     *
     * @param stockCode hisse borsa kodu (orn. "THYAO", "GARAN")
     * @return katilim endeksinde ise {@code true}, degilse {@code false}
     */
    public boolean isKatilim(String stockCode) {
        if (stockCode == null) return false;
        return katilimCodes.contains(stockCode);
    }

    /**
     * Katilim endeksindeki tum hisse kodlarini dondurur.
     *
     * @return katilim endeksi hisse kodlari (immutable set)
     */
    public Set<String> getKatilimCodes() {
        return katilimCodes;
    }
}
