package com.scyborsa.api.service.kap;

import com.scyborsa.api.dto.fintables.FintablesAgendaItemDto;
import com.scyborsa.api.dto.fintables.FintablesTopicFeedItemDto;
import com.scyborsa.api.dto.fintables.FintablesTopicFeedResponseDto;
import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.dto.kap.KapRelatedSymbolDto;
import com.scyborsa.api.service.client.FintablesApiClient;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fintables agenda ve topic-feed verilerini dashboard haber kartlarina entegre eden servis.
 *
 * <p>Fintables API'den alinan verileri {@link KapNewsItemDto} formatina donusturur,
 * kategorize eder ve TradingView verileriyle birlestirir.</p>
 *
 * <p>Volatile cache ile adaptif TTL kullanir (seans ici 120s, seans disi 600s).
 * Hata durumunda graceful degradation — stale cache veya bos liste doner.</p>
 *
 * <p>Dedup stratejisi: KAP haberleri icin 30 dakikalik zaman penceresi + ortak sembol
 * eslestirmesi. Fintables tercih edilir (note alani zenginlestirmeli).</p>
 *
 * @see FintablesApiClient
 * @see KapNewsClient
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FintablesNewsService {

    /** Istanbul saat dilimi (Europe/Istanbul). */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    /** Turkce tarih-saat formatlayici (orn: "03 Mart 2026 14:30"). */
    private static final DateTimeFormatter TR_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm", new Locale("tr", "TR"));
    /** Turkce sadece tarih formatlayici (orn: "03 Mart 2026"). */
    private static final DateTimeFormatter TR_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("tr", "TR"));

    /** Dedup icin zaman penceresi: 30 dakika (1800 saniye). */
    private static final long DEDUP_BUCKET_SECONDS = 1800;

    /** Seans ici cache TTL (120 saniye). */
    private static final long CACHE_TTL_LIVE_MS = 120_000;
    /** Seans disi cache TTL (600 saniye). */
    private static final long CACHE_TTL_OFFHOURS_MS = 600_000;

    /** Fintables API client (agenda + topic-feed). */
    private final FintablesApiClient fintablesApiClient;

    /** Volatile cache — Haberler karti item'lari. */
    private volatile List<KapNewsItemDto> cachedMarketItems;
    /** Volatile cache — KAP Bildirimleri karti item'lari. */
    private volatile List<KapNewsItemDto> cachedKapItems;
    /** Volatile cache — Dunya Haberleri karti item'lari. */
    private volatile List<KapNewsItemDto> cachedWorldItems;
    /** Cache son yenilenme zamani (epoch milisaniye). */
    private volatile long cacheTimestamp;

    /**
     * Haberler karti icin Fintables item'larini doner.
     *
     * <p>post, newsletter, next_sheet ve dividend turundeki ogeleri icerir.</p>
     *
     * @return market news item listesi (bos olabilir, null donmez)
     */
    public List<KapNewsItemDto> getMarketNewsItems() {
        ensureCache();
        List<KapNewsItemDto> items = cachedMarketItems;
        return items != null ? items : Collections.emptyList();
    }

    /**
     * KAP Bildirimleri karti icin Fintables item'larini doner.
     *
     * <p>topic-feed'deki news (kap_id'li) turundeki ogeleri icerir.
     * Bu ogeler insan yazimi note ile zenginlestirilmistir.</p>
     *
     * @return KAP news item listesi (bos olabilir, null donmez)
     */
    public List<KapNewsItemDto> getKapNewsItems() {
        ensureCache();
        List<KapNewsItemDto> items = cachedKapItems;
        return items != null ? items : Collections.emptyList();
    }

    /**
     * Dunya Haberleri karti icin Fintables item'larini doner.
     *
     * <p>agenda'daki macro turundeki ogeleri icerir (TR/US/EU makro takvim).</p>
     *
     * @return world news item listesi (bos olabilir, null donmez)
     */
    public List<KapNewsItemDto> getWorldNewsItems() {
        ensureCache();
        List<KapNewsItemDto> items = cachedWorldItems;
        return items != null ? items : Collections.emptyList();
    }

    /**
     * TradingView KAP haberleriyle Fintables KAP haberlerini birlestirir ve dedup uygular.
     *
     * <p>Dedup stratejisi: 30 dakikalik zaman penceresi + ortak sembol eslestirmesi.
     * Ayni bucket + ortak hisse kodu = duplicate → Fintables tercih edilir.</p>
     *
     * @param tvItems    TradingView'dan gelen KAP haberleri
     * @param ftItems    Fintables'ten gelen KAP haberleri
     * @return birlestirilmis ve dedup uygulanmis liste (yayinlanma zamanina gore sirali)
     */
    public static List<KapNewsItemDto> mergeAndDedup(List<KapNewsItemDto> tvItems,
                                                      List<KapNewsItemDto> ftItems) {
        if (ftItems == null || ftItems.isEmpty()) {
            return tvItems != null ? tvItems : Collections.emptyList();
        }
        if (tvItems == null || tvItems.isEmpty()) {
            return ftItems;
        }

        // Fintables item'larin dedup key'lerini topla
        Set<String> ftDedupKeys = new HashSet<>();
        for (KapNewsItemDto ftItem : ftItems) {
            Set<String> symbols = extractSymbolCodes(ftItem);
            if (ftItem.getPublished() != null && !symbols.isEmpty()) {
                long bucket = ftItem.getPublished() / DEDUP_BUCKET_SECONDS;
                for (String symbol : symbols) {
                    ftDedupKeys.add(bucket + ":" + symbol);
                }
            }
        }

        // TV item'lardan duplicate olanlari filtrele
        List<KapNewsItemDto> filteredTv = new ArrayList<>();
        for (KapNewsItemDto tvItem : tvItems) {
            if (isDuplicate(tvItem, ftDedupKeys)) {
                log.debug("[DEDUP] TV item filtre edildi: id={}, title={}", tvItem.getId(), tvItem.getTitle());
            } else {
                filteredTv.add(tvItem);
            }
        }

        // Merge ve sort
        return mergeByTime(filteredTv, ftItems);
    }

    /**
     * Iki listeyi yayinlanma zamanina gore birlestirir (en yeni en ustte).
     *
     * <p>Dedup uygulamaz — farkli kaynaklardan gelen haberler icin kullanilir.</p>
     *
     * @param list1 birinci liste
     * @param list2 ikinci liste
     * @return birlestirilmis liste (yayinlanma zamanina gore azalan sirali)
     */
    public static List<KapNewsItemDto> mergeByTime(List<KapNewsItemDto> list1,
                                                     List<KapNewsItemDto> list2) {
        if (list2 == null || list2.isEmpty()) {
            return list1 != null ? list1 : Collections.emptyList();
        }
        if (list1 == null || list1.isEmpty()) {
            return list2;
        }

        return Stream.concat(list1.stream(), list2.stream())
                .sorted(Comparator.comparingLong(
                        (KapNewsItemDto item) -> item.getPublished() != null ? item.getPublished() : 0L)
                        .reversed())
                .collect(Collectors.toList());
    }

    // ─── Private methods ───────────────────────────────────────────────────

    /**
     * Cache'in guncel oldugundan emin olur. TTL dolmussa yeniler.
     */
    private void ensureCache() {
        long ttl = BistCacheUtils.getAdaptiveTTL(CACHE_TTL_LIVE_MS, CACHE_TTL_OFFHOURS_MS);
        if ((System.currentTimeMillis() - cacheTimestamp) < ttl && cachedMarketItems != null) {
            return;
        }
        refreshCache();
    }

    /**
     * Fintables API'den veri ceker, donusturur ve cache'e yazar.
     *
     * <p>Hata durumunda mevcut cache korunur (stale cache fallback).
     * Ilk cagri basarisiz olursa bos listeler kullanilir.</p>
     */
    private synchronized void refreshCache() {
        // Double-check: baska thread coktan yenilemis olabilir
        long ttl = BistCacheUtils.getAdaptiveTTL(CACHE_TTL_LIVE_MS, CACHE_TTL_OFFHOURS_MS);
        if ((System.currentTimeMillis() - cacheTimestamp) < ttl && cachedMarketItems != null) {
            return;
        }

        List<KapNewsItemDto> marketItems = new ArrayList<>();
        List<KapNewsItemDto> kapItems = new ArrayList<>();
        List<KapNewsItemDto> worldItems = new ArrayList<>();

        // Baslik bazli dedup seti — ayni baslikli item'lar tekrarlanmaz
        Set<String> seenTitles = new HashSet<>();

        // 1. Topic feed
        try {
            FintablesTopicFeedResponseDto feedResponse = fintablesApiClient.getTopicFeed(50);
            if (feedResponse != null && feedResponse.getResults() != null) {
                for (FintablesTopicFeedItemDto item : feedResponse.getResults()) {
                    categorizeTopicFeedItem(item, marketItems, kapItems, seenTitles);
                }
            }
        } catch (Exception e) {
            log.warn("[FINTABLES-NEWS] Topic feed alinamadi", e);
        }

        // 2. Agenda (thisWeek + nextWeek)
        try {
            List<FintablesAgendaItemDto> thisWeek = fintablesApiClient.getAgenda("thisWeek");
            List<FintablesAgendaItemDto> nextWeek = fintablesApiClient.getAgenda("nextWeek");

            List<FintablesAgendaItemDto> allAgenda = new ArrayList<>();
            if (thisWeek != null) allAgenda.addAll(thisWeek);
            if (nextWeek != null) allAgenda.addAll(nextWeek);

            for (FintablesAgendaItemDto item : allAgenda) {
                categorizeAgendaItem(item, marketItems, worldItems, seenTitles);
            }
        } catch (Exception e) {
            log.warn("[FINTABLES-NEWS] Agenda alinamadi", e);
        }

        // Sort: en yeni en ustte
        Comparator<KapNewsItemDto> byTimeDesc = Comparator.comparingLong(
                (KapNewsItemDto i) -> i.getPublished() != null ? i.getPublished() : 0L).reversed();
        marketItems.sort(byTimeDesc);
        kapItems.sort(byTimeDesc);
        worldItems.sort(byTimeDesc);

        this.cachedMarketItems = marketItems;
        this.cachedKapItems = kapItems;
        this.cachedWorldItems = worldItems;
        this.cacheTimestamp = System.currentTimeMillis();

        log.info("[FINTABLES-NEWS] Cache yenilendi: market={}, kap={}, world={}",
                marketItems.size(), kapItems.size(), worldItems.size());
    }

    /**
     * Topic feed item'ini uygun kategoriye atar.
     *
     * <p>Filtreleme: "fintables" iceren basliklar atlanir, ayni baslik tekrarlanmaz.</p>
     *
     * @param item        Fintables topic feed item
     * @param marketItems Haberler karti listesi
     * @param kapItems    KAP Bildirimleri karti listesi
     * @param seenTitles  baslik dedup seti
     */
    private void categorizeTopicFeedItem(FintablesTopicFeedItemDto item,
                                          List<KapNewsItemDto> marketItems,
                                          List<KapNewsItemDto> kapItems,
                                          Set<String> seenTitles) {
        if (item == null || item.getType() == null) return;

        // "fintables" iceren basliklar filtrelenir (self-promotional icerik)
        if (containsFintables(item.getTitle())) {
            log.debug("[FINTABLES-NEWS] Fintables icerigi filtrelendi: {}", item.getTitle());
            return;
        }

        switch (item.getType()) {
            case "post", "newsletter" -> {
                KapNewsItemDto dto = transformTopicFeedItem(item);
                if (isNotDuplicateTitle(dto.getTitle(), seenTitles)) {
                    marketItems.add(dto);
                }
            }
            case "news" -> {
                // KAP haberlerinde note icerigini de kontrol et
                if (item.getNews() != null && containsFintables(item.getNews().getNote())) {
                    log.debug("[FINTABLES-NEWS] KAP note fintables icerigi filtrelendi: {}", item.getTitle());
                    return;
                }
                KapNewsItemDto dto = transformTopicFeedNewsItem(item);
                if (isNotDuplicateTitle(dto.getTitle(), seenTitles)) {
                    kapItems.add(dto);
                }
            }
            default -> log.debug("[FINTABLES-NEWS] Bilinmeyen topic feed turu: {}", item.getType());
        }
    }

    /**
     * Agenda item'ini uygun kategoriye atar.
     *
     * <p>Filtreleme: "fintables" iceren basliklar atlanir, ayni baslik tekrarlanmaz.</p>
     *
     * @param item        Fintables agenda item
     * @param marketItems Haberler karti listesi
     * @param worldItems  Dunya Haberleri karti listesi
     * @param seenTitles  baslik dedup seti
     */
    private void categorizeAgendaItem(FintablesAgendaItemDto item,
                                       List<KapNewsItemDto> marketItems,
                                       List<KapNewsItemDto> worldItems,
                                       Set<String> seenTitles) {
        if (item == null || item.getType() == null) return;

        // "fintables" iceren basliklar filtrelenir
        if (containsFintables(item.getTitle())) {
            log.debug("[FINTABLES-NEWS] Agenda fintables icerigi filtrelendi: {}", item.getTitle());
            return;
        }

        switch (item.getType()) {
            case "next_sheet", "dividend" -> {
                KapNewsItemDto dto = transformAgendaItem(item);
                if (isNotDuplicateTitle(dto.getTitle(), seenTitles)) {
                    marketItems.add(dto);
                }
            }
            case "macro" -> {
                KapNewsItemDto dto = transformAgendaItem(item);
                if (isNotDuplicateTitle(dto.getTitle(), seenTitles)) {
                    worldItems.add(dto);
                }
            }
            case "webinar" -> { /* Fintables-specific, gosterilmez */ }
            default -> log.debug("[FINTABLES-NEWS] Bilinmeyen agenda turu: {}", item.getType());
        }
    }

    /**
     * Basligin "fintables" kelimesini icerip icermedigini kontrol eder (case-insensitive).
     *
     * @param text kontrol edilecek metin (nullable)
     * @return true ise "fintables" iceriyor (filtrelenmeli)
     */
    private boolean containsFintables(String text) {
        if (text == null || text.isBlank()) return false;
        return text.toLowerCase(Locale.ROOT).contains("fintables");
    }

    /**
     * Basligin daha once gorulup gorulmedigini kontrol eder ve sete ekler.
     *
     * <p>Normalize: lowercase + trim ile karsilastirilir.</p>
     *
     * @param title      kontrol edilecek baslik (nullable)
     * @param seenTitles gorulen basliklar seti
     * @return true ise yeni baslik (listeye eklenebilir), false ise duplicate
     */
    private boolean isNotDuplicateTitle(String title, Set<String> seenTitles) {
        if (title == null || title.isBlank()) return true;
        String normalized = title.toLowerCase(Locale.ROOT).trim();
        if (seenTitles.contains(normalized)) {
            log.debug("[FINTABLES-NEWS] Duplicate baslik filtrelendi: {}", title);
            return false;
        }
        seenTitles.add(normalized);
        return true;
    }

    /**
     * Topic feed post/newsletter item'ini KapNewsItemDto'ya donusturur.
     *
     * @param item Fintables topic feed item (post veya newsletter)
     * @return donusturulmus DTO
     */
    private KapNewsItemDto transformTopicFeedItem(FintablesTopicFeedItemDto item) {
        KapNewsItemDto dto = new KapNewsItemDto();
        dto.setId("ft-" + item.getId());
        dto.setTitle(item.getTitle());
        dto.setProvider("Piyasa");
        dto.setSource("Piyasa");
        dto.setStoryPath(null);
        dto.setPublished(parsePublishedAt(resolveItemDate(item)));
        dto.setFormattedPublished(formatEpoch(dto.getPublished()));
        dto.setRelatedSymbols(extractTopicSymbols(item.getTopics()));
        return dto;
    }

    /**
     * Topic feed news (KAP) item'ini KapNewsItemDto'ya donusturur.
     *
     * <p>KAP haberlerinde note alani varsa baslik olarak tercih edilir (zenginlestirme).</p>
     *
     * @param item Fintables topic feed item (news turu)
     * @return donusturulmus DTO
     */
    private KapNewsItemDto transformTopicFeedNewsItem(FintablesTopicFeedItemDto item) {
        KapNewsItemDto dto = new KapNewsItemDto();

        FintablesTopicFeedItemDto.NewsData news = item.getNews();
        if (news != null) {
            dto.setId("ft-kap-" + news.getId());
            // Note varsa note tercih et (insan yazimi ozet), yoksa title
            String title = (news.getNote() != null && !news.getNote().isBlank())
                    ? news.getNote()
                    : (news.getNoteTitle() != null && !news.getNoteTitle().isBlank())
                            ? news.getNoteTitle()
                            : item.getTitle();
            dto.setTitle(title);

            // News symbols varsa onlari kullan
            if (news.getSymbols() != null && !news.getSymbols().isEmpty()) {
                dto.setRelatedSymbols(news.getSymbols().stream()
                        .map(s -> {
                            KapRelatedSymbolDto sym = new KapRelatedSymbolDto();
                            sym.setSymbol("BIST:" + s.getCode());
                            return sym;
                        })
                        .collect(Collectors.toList()));
            } else {
                dto.setRelatedSymbols(extractTopicSymbols(item.getTopics()));
            }
        } else {
            log.warn("[FINTABLES-NEWS] news item has null NewsData: id={}", item.getId());
            dto.setId("ft-kap-null-" + item.getId());
            dto.setTitle(item.getTitle());
            dto.setRelatedSymbols(extractTopicSymbols(item.getTopics()));
        }

        dto.setProvider("KAP");
        dto.setSource("KAP");
        dto.setStoryPath(null);
        dto.setPublished(parsePublishedAt(resolveItemDate(item)));
        dto.setFormattedPublished(formatEpoch(dto.getPublished()));
        return dto;
    }

    /**
     * Agenda item'ini KapNewsItemDto'ya donusturur.
     *
     * @param item Fintables agenda item
     * @return donusturulmus DTO
     */
    private KapNewsItemDto transformAgendaItem(FintablesAgendaItemDto item) {
        KapNewsItemDto dto = new KapNewsItemDto();
        String titleHash = item.getTitle() != null ? String.valueOf(item.getTitle().hashCode()) : "0";
        dto.setId("ft-agenda-" + item.getType() + "-" + item.getDay() + "-" + titleHash);
        dto.setTitle(buildAgendaTitle(item));
        dto.setProvider("G\u00fcndem");
        dto.setSource(resolveAgendaSource(item.getType()));
        dto.setStoryPath(null);
        dto.setPublished(parseAgendaDateTime(item.getDay(), item.getTime()));
        dto.setFormattedPublished(formatAgendaDate(item.getDay(), item.getTime()));
        dto.setRelatedSymbols(extractAgendaSymbols(item));
        return dto;
    }

    /**
     * Agenda item'i icin kaynak adini belirler.
     *
     * @param type agenda item turu
     * @return kaynak adi
     */
    private String resolveAgendaSource(String type) {
        return switch (type) {
            case "next_sheet" -> "Gündem";
            case "dividend" -> "Gündem";
            case "macro" -> "Makro";
            default -> "Gündem";
        };
    }

    /**
     * Agenda item'i icin zenginlestirilmis baslik olusturur.
     *
     * <p>Data label-value ciftlerini basliklara ekler (orn: "THYAO — Bilanço Dönemi: 2025/12").</p>
     *
     * @param item agenda item
     * @return olusturulmus baslik
     */
    private String buildAgendaTitle(FintablesAgendaItemDto item) {
        StringBuilder sb = new StringBuilder(item.getTitle() != null ? item.getTitle() : "");
        if (item.getData() != null && !item.getData().isEmpty()) {
            for (FintablesAgendaItemDto.AgendaData data : item.getData()) {
                if (data.getValue() != null && !data.getValue().isBlank()) {
                    String label = data.getLabel() != null ? data.getLabel() : "";
                    sb.append(" — ").append(label).append(": ").append(data.getValue());
                    break; // Sadece ilk anlamli data'yi ekle, uzun baslik onle
                }
            }
        }
        return sb.toString();
    }

    /**
     * Agenda item'inden sembol cikarir.
     *
     * <p>imageFallbackText genellikle hisse kodu icerir (orn: "THYAO").</p>
     *
     * @param item agenda item
     * @return sembol listesi (bos olabilir)
     */
    private List<KapRelatedSymbolDto> extractAgendaSymbols(FintablesAgendaItemDto item) {
        if (item.getImageFallbackText() != null && !item.getImageFallbackText().isBlank()
                && item.getImageFallbackText().matches("[A-Z0-9]{2,6}")) {
            KapRelatedSymbolDto sym = new KapRelatedSymbolDto();
            sym.setSymbol("BIST:" + item.getImageFallbackText());
            return List.of(sym);
        }
        return Collections.emptyList();
    }

    /**
     * Topic feed item'indan yayinlanma tarihini cozumler.
     *
     * <p>Fintables API "date" alanini kullanir. Yoksa "published_at" alanina duser.</p>
     *
     * @param item topic feed item
     * @return ISO-8601 tarih string'i (nullable)
     */
    private String resolveItemDate(FintablesTopicFeedItemDto item) {
        if (item.getDate() != null && !item.getDate().isBlank()) {
            return item.getDate();
        }
        return item.getPublishedAt();
    }

    /**
     * Topic referanslarindan sembol tipindeki ogeleri KapRelatedSymbolDto listesine donusturur.
     *
     * @param topics topic referans listesi (nullable)
     * @return sembol DTO listesi
     */
    private List<KapRelatedSymbolDto> extractTopicSymbols(List<FintablesTopicFeedItemDto.TopicRef> topics) {
        if (topics == null || topics.isEmpty()) {
            return Collections.emptyList();
        }
        return topics.stream()
                .filter(t -> "symbol".equals(t.getType()) && t.getId() != null)
                .map(t -> {
                    KapRelatedSymbolDto sym = new KapRelatedSymbolDto();
                    sym.setSymbol("BIST:" + t.getId());
                    return sym;
                })
                .collect(Collectors.toList());
    }

    /**
     * ISO-8601 publishedAt stringini epoch saniyeye donusturur.
     *
     * @param publishedAt ISO-8601 zaman (nullable)
     * @return epoch saniye, parse edilemezse null (sort'ta sona atilir)
     */
    private Long parsePublishedAt(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(publishedAt).toEpochSecond();
        } catch (DateTimeParseException e) {
            try {
                // "2026-03-11T14:30:00" gibi zone bilgisi olmayan formati dene
                return java.time.LocalDateTime.parse(publishedAt)
                        .atZone(ISTANBUL_ZONE)
                        .toEpochSecond();
            } catch (DateTimeParseException e2) {
                log.warn("[FINTABLES-NEWS] publishedAt parse edilemedi: {}", publishedAt);
                return null;
            }
        }
    }

    /**
     * Agenda gunu ve saatini epoch saniyeye donusturur.
     *
     * @param day  tarih (yyyy-MM-dd)
     * @param time saat (HH:mm, nullable)
     * @return epoch saniye, parse edilemezse null
     */
    private Long parseAgendaDateTime(String day, String time) {
        if (day == null || day.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(day);
            LocalTime localTime = (time != null && !time.isBlank())
                    ? LocalTime.parse(time) : LocalTime.of(9, 0);
            return date.atTime(localTime).atZone(ISTANBUL_ZONE).toEpochSecond();
        } catch (Exception e) {
            log.warn("[FINTABLES-NEWS] Agenda tarih parse edilemedi: day={}, time={}", day, time);
            return null;
        }
    }

    /**
     * Epoch saniyeyi Turkce formata donusturur.
     *
     * @param epochSecond epoch saniye (nullable)
     * @return formatlanmis zaman veya "-"
     */
    private String formatEpoch(Long epochSecond) {
        if (epochSecond == null) return "-";
        return Instant.ofEpochSecond(epochSecond)
                .atZone(ISTANBUL_ZONE)
                .format(TR_FORMATTER);
    }

    /**
     * Agenda tarih ve saatini Turkce formata donusturur.
     *
     * @param day  tarih (yyyy-MM-dd)
     * @param time saat (HH:mm, nullable)
     * @return formatlanmis tarih-saat
     */
    private String formatAgendaDate(String day, String time) {
        try {
            LocalDate date = LocalDate.parse(day);
            if (time != null && !time.isBlank()) {
                LocalTime localTime = LocalTime.parse(time);
                return date.atTime(localTime).atZone(ISTANBUL_ZONE).format(TR_FORMATTER);
            }
            return date.atStartOfDay(ISTANBUL_ZONE).format(TR_DATE_FORMATTER);
        } catch (Exception e) {
            return day != null ? day : "-";
        }
    }

    /**
     * Bir KapNewsItemDto'dan sembol kodlarini cikarir (dedup icin).
     *
     * @param item haber item
     * @return sembol kodlari seti (orn: {"THYAO", "GARAN"})
     */
    private static Set<String> extractSymbolCodes(KapNewsItemDto item) {
        if (item.getRelatedSymbols() == null) return Collections.emptySet();
        Set<String> codes = new HashSet<>();
        for (KapRelatedSymbolDto sym : item.getRelatedSymbols()) {
            if (sym.getSymbol() != null) {
                // "BIST:THYAO" → "THYAO"
                String code = sym.getSymbol().contains(":")
                        ? sym.getSymbol().substring(sym.getSymbol().indexOf(':') + 1)
                        : sym.getSymbol();
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * TV item'inin Fintables dedup key'leriyle eslesip eslesmedigini kontrol eder.
     *
     * @param tvItem      TradingView haber item
     * @param ftDedupKeys Fintables dedup key seti
     * @return true ise duplicate (filtrelenmeli)
     */
    private static boolean isDuplicate(KapNewsItemDto tvItem, Set<String> ftDedupKeys) {
        if (tvItem.getPublished() == null) return false;
        Set<String> symbols = extractSymbolCodes(tvItem);
        if (symbols.isEmpty()) return false;

        long bucket = tvItem.getPublished() / DEDUP_BUCKET_SECONDS;
        for (String symbol : symbols) {
            if (ftDedupKeys.contains(bucket + ":" + symbol)) {
                return true;
            }
        }
        return false;
    }
}
