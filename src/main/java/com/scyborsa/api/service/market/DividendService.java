package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.market.DividendDto;
import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Temettu (dividend) veri servisi.
 *
 * <p>Velzon API'sinden temettu verilerini ceker, adaptive TTL ile cache'ler
 * ve odeme tarihine gore siralar.</p>
 *
 * <p>Cache stratejisi: Seans saatlerinde 1 saat, seans disinda 6 saat
 * ({@link BistCacheUtils#getAdaptiveTTL(long, long)}).</p>
 *
 * <p>Veri akisi: Oncelikle {@code /api/dividends/upcoming} endpoint'i denenir.
 * Bos donerse {@code /api/dividends?limit=10} fallback olarak kullanilir.</p>
 *
 * @see com.scyborsa.api.dto.market.DividendDto
 * @see com.scyborsa.api.controller.DividendController
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendService {

    private final VelzonApiClient velzonApiClient;
    private final ObjectMapper objectMapper;

    private static final String ALL_PATH = "/api/dividends?limit=100";
    private static final int MAX_FUTURE = 5;
    private static final int MAX_PAST = 5;

    /** Cache'lenmis temettu listesi. */
    private volatile List<DividendDto> cachedDividends;
    /** Cache'in son guncellenme zamani (epoch ms). */
    private volatile long cacheTimestamp;
    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /**
     * Tum temettu verilerini dondurur (cache'den).
     *
     * @return temettu listesi, odeme tarihine ve verime gore siralanmis; veri yoksa bos liste
     */
    public List<DividendDto> getDividends() {
        refreshCacheIfStale();
        List<DividendDto> dividends = cachedDividends;
        return dividends != null ? dividends : List.of();
    }

    /**
     * Cache suresi dolduysa Velzon API'den yeni veri ceker.
     * Double-check locking ile thread-safe cache yenileme.
     */
    private void refreshCacheIfStale() {
        long ttl = BistCacheUtils.getAdaptiveTTL(3_600_000L, 21_600_000L);
        long now = System.currentTimeMillis();

        if (cachedDividends != null && (now - cacheTimestamp) < ttl) {
            return;
        }

        synchronized (cacheLock) {
            ttl = BistCacheUtils.getAdaptiveTTL(3_600_000L, 21_600_000L);
            now = System.currentTimeMillis();
            if (cachedDividends != null && (now - cacheTimestamp) < ttl) {
                return;
            }

            log.info("[TEMETTÜ] Cache yenileniyor (TTL: {}ms)", ttl);
            List<DividendDto> freshData = fetchDividends();

            if (freshData != null && !freshData.isEmpty()) {
                this.cachedDividends = Collections.unmodifiableList(freshData);
                this.cacheTimestamp = System.currentTimeMillis();
                log.info("[TEMETTÜ] Cache guncellendi: {} temettu", freshData.size());
            } else {
                if (cachedDividends != null) {
                    // Mevcut cache var — tam TTL backoff
                    this.cacheTimestamp = System.currentTimeMillis();
                    log.warn("[TEMETTÜ] Veri çekilemedi, mevcut cache korunuyor. TTL sonrası yeniden denenecek.");
                } else {
                    // Soğuk başlatma hatası — 1 dakika sonra yeniden dene
                    this.cacheTimestamp = System.currentTimeMillis() - ttl + 60_000L;
                    log.warn("[TEMETTÜ] Soğuk başlatmada veri çekilemedi, 1 dakika sonra yeniden denenecek.");
                }
            }
        }
    }

    /**
     * Velzon API'den temettü verilerini çeker.
     *
     * <p>Tüm temettüler çekilir, gelecek ve geçmiş olarak ayrılır.
     * Gelecek max {@value MAX_FUTURE}, geçmiş max {@value MAX_PAST} kayıt döner.
     * Gelecek temettüler önce, geçmiş sonra sıralanır.</p>
     *
     * @return temettü listesi (max {@value MAX_FUTURE}+{@value MAX_PAST}); hata durumunda boş liste
     */
    private List<DividendDto> fetchDividends() {
        try {
            String responseBody = velzonApiClient.get(ALL_PATH);
            List<DividendDto> all = parseJsonArray(responseBody);
            log.info("[TEMETTÜ] API'den {} temettü alındı", all.size());

            if (all.isEmpty()) return List.of();

            String today = LocalDate.now(ZoneId.of("Europe/Istanbul")).toString();

            // Gelecek ve geçmiş olarak ayır
            List<DividendDto> future = new ArrayList<>();
            List<DividendDto> past = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (DividendDto d : all) {
                // Duplicate engelle
                if (d.getStockCode() == null || !seen.add(d.getStockCode())) continue;
                String date = toDateOnly(d.getPaymentDate() != null ? d.getPaymentDate() : d.getExDividendDate());
                if (date != null && date.compareTo(today) >= 0) {
                    future.add(d);
                } else {
                    past.add(d);
                }
            }

            // Geçmiş: en yeni önce (tarihe göre DESC) — sonra max 5 al
            past.sort(Comparator.comparing(
                    (DividendDto d) -> toDateOnly(d.getPaymentDate()) != null ? toDateOnly(d.getPaymentDate()) : "0000",
                    Comparator.reverseOrder()));
            if (past.size() > MAX_PAST) past = past.subList(0, MAX_PAST);

            // Gelecek: yakın tarih önce (tarihe göre ASC) — sonra max 5 al
            future.sort(Comparator.comparing(
                    (DividendDto d) -> toDateOnly(d.getPaymentDate()) != null ? toDateOnly(d.getPaymentDate()) : "9999",
                    Comparator.naturalOrder()));
            if (future.size() > MAX_FUTURE) future = future.subList(0, MAX_FUTURE);

            // Birleştir: geçmiş + gelecek (kronolojik sıra — eski→yeni)
            List<DividendDto> result = new ArrayList<>();
            result.addAll(past);
            result.addAll(future);

            // Tümünü tarihe göre DESC sırala (yeni en üstte, eski en altta)
            result.sort(Comparator.comparing(
                    (DividendDto d) -> {
                        String dt = toDateOnly(d.getPaymentDate());
                        return dt != null ? dt : toDateOnly(d.getExDividendDate()) != null ? toDateOnly(d.getExDividendDate()) : "0000";
                    },
                    Comparator.reverseOrder()));

            log.info("[TEMETTÜ] Toplam: {} temettü (gelecek: {}, geçmiş: {})",
                    result.size(), future.size(), past.size());

            return result;
        } catch (Exception e) {
            log.error("[TEMETTÜ] Veri çekme başarısız", e);
            return List.of();
        }
    }

    /**
     * JSON array response'unu parse eder.
     *
     * <p>Response dogrudan bir JSON array'dir (wrapper obje yok).</p>
     *
     * @param responseBody ham JSON string
     * @return parse edilmis temettu listesi; hata durumunda bos liste
     */
    private List<DividendDto> parseJsonArray(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isArray()) {
                log.warn("[TEMETTÜ] Response JSON array degil");
                return new ArrayList<>();
            }

            List<DividendDto> dividends = new ArrayList<>();
            for (JsonNode item : root) {
                // Tarih: önce API alanlarını dene, yoksa rawData timestamp'lardan çıkar
                String exDivDate = getTextOrNull(item, "exDividendDate");
                String payDate = getTextOrNull(item, "paymentDate");

                if ((exDivDate == null || payDate == null) && item.has("rawData")) {
                    try {
                        JsonNode rawArray = objectMapper.readTree(item.get("rawData").asText());
                        if (rawArray != null && rawArray.isArray()) {
                            // rawData[1] = ex-dividend timestamp, rawData[7] = payment timestamp
                            if (exDivDate == null && rawArray.size() > 1 && rawArray.get(1).isNumber()) {
                                exDivDate = timestampToDate(rawArray.get(1).asLong());
                            }
                            if (payDate == null && rawArray.size() > 7 && rawArray.get(7).isNumber()) {
                                payDate = timestampToDate(rawArray.get(7).asLong());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[TEMETTÜ] rawData parse hatası: {}", item.get("rawData"));
                    }
                }

                DividendDto dto = DividendDto.builder()
                        .stockCode(stripBistPrefix(getTextOrNull(item, "symbol")))
                        .companyName(getTextOrNull(item, "companyName"))
                        .dividendAmount(getDoubleOrNull(item, "dividendAmount"))
                        .dividendYield(getDoubleOrNull(item, "dividendYield"))
                        .exDividendDate(exDivDate)
                        .paymentDate(payDate)
                        .currency(getTextOrNull(item, "currency"))
                        .build();
                dividends.add(dto);
            }

            return dividends;
        } catch (Exception e) {
            log.error("[TEMETTÜ] JSON parse hatasi", e);
            return new ArrayList<>();
        }
    }

    /**
     * Unix timestamp'ı ISO tarih string'ine dönüştürür (Europe/Istanbul).
     *
     * @param timestamp Unix timestamp (saniye)
     * @return ISO tarih string'i (ör. "2025-06-13"); hata durumunda null
     */
    private String timestampToDate(long timestamp) {
        try {
            return LocalDate.ofInstant(
                    Instant.ofEpochSecond(timestamp),
                    ZoneId.of("Europe/Istanbul")
            ).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tarih string'inden sadece YYYY-MM-DD kısmını alır.
     * "2026-03-18T11:59:00" → "2026-03-18", "2026-03-31" → "2026-03-31"
     *
     * @param dateStr tarih string'i (ISO veya sadece tarih)
     * @return YYYY-MM-DD formatında tarih; null ise null
     */
    private String toDateOnly(String dateStr) {
        if (dateStr == null) return null;
        return dateStr.length() >= 10 ? dateStr.substring(0, 10) : dateStr;
    }

    /**
     * "BIST:" prefix'ini symbol'den cikarir.
     *
     * @param symbol ham sembol (orn. "BIST:THYAO")
     * @return prefix'siz hisse kodu (orn. "THYAO"); null ise null doner
     */
    private String stripBistPrefix(String symbol) {
        if (symbol == null) return null;
        return symbol.startsWith("BIST:") ? symbol.substring(5) : symbol;
    }

    /**
     * JSON node'undan string deger okur.
     *
     * @param node  JSON node
     * @param field alan adi
     * @return string deger veya null
     */
    private String getTextOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? val.asText() : null;
    }

    /**
     * JSON node'undan double deger okur.
     *
     * @param node  JSON node
     * @param field alan adi
     * @return double deger veya null
     */
    private Double getDoubleOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && val.isNumber()) ? val.asDouble() : null;
    }
}
