package com.scyborsa.api.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring profil yardımcı sınıfı.
 * Aktif profil kontrolü için inject edilir.
 */
@Slf4j
@Component
public class ProfileUtils {

    /** Aktif Spring profil adi (application.yml'den inject edilir). */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /** Gelistirme profil sabiti. */
    private static final String PROFILE_DEV = "dev";
    /** Uzun gelistirme profil sabiti. */
    private static final String PROFILE_DEVELOPMENT = "development";
    /** Yerel gelistirme profil sabiti. */
    private static final String PROFILE_LOCAL = "local";
    /** Production profil sabiti. */
    private static final String PROFILE_PROD = "prod";
    /** Uzun production profil sabiti. */
    private static final String PROFILE_PRODUCTION = "production";
    /** Test profil sabiti. */
    private static final String PROFILE_TEST = "test";

    /**
     * Aktif profilin gelistirme ortami (dev, development, local) olup olmadigini kontrol eder.
     *
     * @return dev ortamindaysa {@code true}
     */
    public boolean isDevProfile() {
        return PROFILE_DEV.equalsIgnoreCase(activeProfile) ||
               PROFILE_DEVELOPMENT.equalsIgnoreCase(activeProfile) ||
               PROFILE_LOCAL.equalsIgnoreCase(activeProfile);
    }

    /**
     * Aktif profilin production ortami (prod, production) olup olmadigini kontrol eder.
     *
     * @return production ortamindaysa {@code true}
     */
    public boolean isProdProfile() {
        return PROFILE_PROD.equalsIgnoreCase(activeProfile) ||
               PROFILE_PRODUCTION.equalsIgnoreCase(activeProfile);
    }

    /**
     * Aktif profilin test ortami olup olmadigini kontrol eder.
     *
     * @return test ortamindaysa {@code true}
     */
    public boolean isTestProfile() {
        return PROFILE_TEST.equalsIgnoreCase(activeProfile);
    }

    /**
     * Gelistirme ortaminda (dev veya test) olup olmadigini kontrol eder.
     *
     * @return dev veya test ortamindaysa {@code true}
     */
    public boolean isDevelopmentEnvironment() {
        return isDevProfile() || isTestProfile();
    }

    /**
     * Production ortaminda olup olmadigini kontrol eder.
     *
     * @return production ortamindaysa {@code true}
     */
    public boolean isProductionEnvironment() {
        return isProdProfile();
    }

    /**
     * Debug modunun aktif olup olmadigini kontrol eder.
     * Dev ve test ortamlarinda debug modu aktiftir.
     *
     * @return debug modu aktifse {@code true}
     */
    public boolean isDebugMode() {
        return isDevProfile() || isTestProfile();
    }

    /**
     * Aktif Spring profil adini dondurur.
     *
     * @return aktif profil adi (ornegin "dev", "prod")
     */
    public String getActiveProfile() {
        return activeProfile;
    }

    /**
     * Aktif profilin verilen profil adiyla eslesip eslesmedigini kontrol eder.
     * Buyuk/kucuk harf duyarsiz karsilastirma yapar.
     *
     * @param profile kontrol edilecek profil adi
     * @return profiller eslesiyorsa {@code true}
     */
    public boolean isProfile(String profile) {
        return profile != null && profile.equalsIgnoreCase(activeProfile);
    }

    /**
     * Aktif profil bilgilerini log'a yazar.
     * Profil adi, dev ortami durumu ve prod ortami durumunu loglar.
     */
    public void logProfileInfo() {
        log.info("Aktif Profil: {}", activeProfile);
        log.info("Dev Environment: {}", isDevelopmentEnvironment());
        log.info("Prod Environment: {}", isProductionEnvironment());
    }
}
