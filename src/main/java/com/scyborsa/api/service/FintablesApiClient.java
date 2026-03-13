package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesApiConfig;
import com.scyborsa.api.dto.fintables.FintablesBrokerageDto;
import com.scyborsa.api.dto.enrichment.FintablesAkdResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesBrokerageAkdDetailDto;
import com.scyborsa.api.dto.enrichment.FintablesBrokerageAkdListDto;
import com.scyborsa.api.dto.enrichment.FintablesOrderbookResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesTakasResponseDto;
import com.scyborsa.api.dto.fintables.FintablesAgendaItemDto;
import com.scyborsa.api.dto.fintables.FintablesTopicFeedResponseDto;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fintables (api.fintables.com) REST API istemcisi.
 *
 * <p>OkHttp kullanarak Fintables API'ye GET istekleri gonderir.
 * Cloudflare bot korumasini asabilmek icin OkHttp tercih edilmistir
 * (Java 11 HttpClient TLS fingerprint'i Cloudflare tarafindan engellenir).</p>
 *
 * <p>Kimlik dogrulama {@code Cookie} header'i uzerinden yapilir.
 * Cookie icinde {@code auth-token=<JWT>} yer alir.</p>
 *
 * <p>Konfigurasyon {@link com.scyborsa.api.config.FintablesApiConfig} uzerinden saglanir
 * (base URL, Bearer token, cookie).</p>
 *
 * @see FintablesApiConfig
 */
@Slf4j
@Service
public class FintablesApiClient {

    /** Fintables API konfigurasyonu (base URL, Bearer token, cookie). */
    private final FintablesApiConfig config;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin OkHttp istemcisi (Cloudflare uyumlu). */
    private final OkHttpClient httpClient;

    /**
     * Constructor injection ile bagimliliklari alir ve OkHttp client'i olusturur.
     *
     * @param config       Fintables API konfigurasyonu (base URL, Bearer token, cookie)
     * @param objectMapper JSON parse icin Jackson ObjectMapper
     */
    public FintablesApiClient(FintablesApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getRequestTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * Belirtilen URL'ye GET istegi yapar ve ham response body'sini doner.
     *
     * <p>URL tam (absolute) olabilir veya path olabilir. Path ise base URL ile birlestirilir.</p>
     *
     * @param url API URL'i (tam URL veya path)
     * @return ham JSON response body string'i
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti veya I/O hatasi durumunda
     */
    public String get(String url) throws Exception {
        String fullUrl = url.startsWith("http") ? url : config.getBaseUrl() + url;

        // SSRF korunma: sadece konfigure edilen domain'e istek yapilabilir
        URI baseUri = URI.create(config.getBaseUrl());
        URI requestUri = URI.create(fullUrl);
        if (!requestUri.getHost().equals(baseUri.getHost())) {
            throw new IllegalArgumentException("Gecersiz API URL: beklenmeyen domain");
        }

        log.debug("Fintables API GET: {}", fullUrl);

        Request.Builder requestBuilder = new Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("Origin", "https://fintables.com")
                .addHeader("Referer", "https://fintables.com/")
                .addHeader("Accept", "application/json, text/plain, */*");

        // Bearer token ile kimlik dogrulama
        String bearerToken = config.getBearerToken();
        if (bearerToken != null && !bearerToken.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + bearerToken);
        }

        // Cookie header ile kimlik dogrulama (Cloudflare uyumlu, fallback)
        String cookie = config.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookie);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            if (statusCode != 200) {
                log.error("Fintables API hatasi: status={}, url={}", statusCode, fullUrl);
                throw new RuntimeException("Fintables API hatasi: " + statusCode);
            }

            String body = response.body() != null ? response.body().string() : "";
            return body;
        }
    }

    /**
     * Belirtilen URL'ye GET istegi yapar ve response'u belirtilen tipe deserialize eder.
     *
     * @param <T>     hedef tip
     * @param url     API URL'i (tam URL veya path)
     * @param typeRef Jackson TypeReference
     * @return deserialize edilmis response nesnesi
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti, I/O veya JSON parse hatasi durumunda
     */
    public <T> T get(String url, TypeReference<T> typeRef) throws Exception {
        String body = get(url);
        return objectMapper.readValue(body, typeRef);
    }

    /**
     * Fintables araci kurum listesini getirir.
     *
     * <p>{@code /brokerages/} endpoint'ine GET istegi yaparak
     * tum araci kurum bilgilerini dondurur.</p>
     *
     * @return araci kurum DTO listesi
     * @throws Exception baglanti, I/O veya JSON parse hatasi durumunda
     */
    public List<FintablesBrokerageDto> getBrokerages() throws Exception {
        log.debug("Fintables brokerages listesi aliniyor");
        return get("/brokerages/", new TypeReference<>() {});
    }

    /**
     * Fintables haftalik ajanda verilerini getirir.
     *
     * <p>{@code /mobile/agenda/} endpoint'ine GET istegi yaparak bilanco, temettu,
     * makro takvim ve webinar gibi ajanda ogelerini dondurur.</p>
     *
     * @param time zaman filtresi ("thisWeek" veya "nextWeek")
     * @return ajanda item listesi
     * @throws Exception baglanti, I/O veya JSON parse hatasi durumunda
     */
    public List<FintablesAgendaItemDto> getAgenda(String time) throws Exception {
        if (time == null || time.isBlank()) {
            throw new IllegalArgumentException("Agenda time parametresi bos olamaz");
        }
        log.debug("Fintables agenda aliniyor: time={}", time);
        return get("/mobile/agenda/?time=" + URLEncoder.encode(time, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    /**
     * Fintables topic feed verilerini getirir.
     *
     * <p>{@code /topic-feed/} endpoint'ine GET istegi yaparak post, KAP news
     * ve newsletter gibi topic feed ogelerini dondurur.</p>
     *
     * @param pageSize sayfa basina item sayisi
     * @return paginated topic feed response
     * @throws Exception baglanti, I/O veya JSON parse hatasi durumunda
     */
    public FintablesTopicFeedResponseDto getTopicFeed(int pageSize) throws Exception {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 1-100 araliginda olmali: " + pageSize);
        }
        log.debug("Fintables topic feed aliniyor: pageSize={}", pageSize);
        return get("/topic-feed/?page_size=" + pageSize + "&for_everyone=1&only_pro=1",
                new TypeReference<>() {});
    }

    /**
     * Hisse bazlı AKD (Aracı Kurum Dağılımı) verisini Fintables'ten getirir.
     * AKD endpoint'i ayri bir JWT token gerektirir ({@code fintables.api.akd-token}).
     *
     * @param company hisse kodu (ör: "GARAN")
     * @param start   başlangıç tarihi (yyyy-MM-dd formatında)
     * @param end     bitiş tarihi (yyyy-MM-dd formatında)
     * @return AKD response DTO'su
     * @throws Exception API çağrısı başarısız olursa
     */
    public FintablesAkdResponseDto getAkd(String company, String start, String end) throws Exception {
        String url = "/mobile/akd/?company=" + URLEncoder.encode(company, StandardCharsets.UTF_8)
                + "&start=" + URLEncoder.encode(start, StandardCharsets.UTF_8)
                + "&end=" + URLEncoder.encode(end, StandardCharsets.UTF_8);
        String body = getWithToken(url, config.getAkdToken());
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    /**
     * Hisse bazlı Takas (saklama dağılımı) verisini Fintables'ten getirir.
     * Takas endpoint'i ayrı bir JWT token gerektirir ({@code fintables.api.takas-token}).
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @param date      tarih (yyyy-MM-dd formatında)
     * @return Takas raw response DTO'su
     * @throws Exception API çağrısı başarısız olursa
     */
    public FintablesTakasResponseDto getTakas(String stockCode, String date) throws Exception {
        String url = "/mobile/custodies/?index=custodian&code="
                + URLEncoder.encode(stockCode, StandardCharsets.UTF_8)
                + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8);
        String body = getWithToken(url, config.getTakasToken());
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    /**
     * Fintables piyasa geneli AKD araci kurum dagilim listesini getirir.
     *
     * @param start baslangic tarihi (YYYY-MM-DD)
     * @param end   bitis tarihi (YYYY-MM-DD)
     * @return araci kurum AKD dagilim listesi
     * @throws Exception API cagrisi basarisiz olursa
     */
    public FintablesBrokerageAkdListDto getBrokerageAkdList(String start, String end) throws Exception {
        String url = "/mobile/brokerages/akd/list/?start="
                + URLEncoder.encode(start, StandardCharsets.UTF_8)
                + "&end=" + URLEncoder.encode(end, StandardCharsets.UTF_8);
        String body = getWithToken(url, config.getBrokerageToken());
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    /**
     * Belirli bir aracı kurumun hisse bazlı AKD dağılımını getirir.
     *
     * <p>{@code /mobile/brokerages/akd/} endpoint'ine kurum kodu ve tarih parametreleri ile
     * GET istegi yaparak hisse bazli alis/satis/net/toplam islem verilerini dondurur.</p>
     *
     * @param brokerage kurum kodu (MLB, YKR vb.)
     * @param start     başlangıç tarihi (YYYY-MM-DD)
     * @param end       bitiş tarihi (YYYY-MM-DD)
     * @return hisse bazlı AKD dağılım verisi
     * @throws Exception API çağrısı başarısız olursa
     */
    public FintablesBrokerageAkdDetailDto getBrokerageAkdDetail(String brokerage, String start, String end)
            throws Exception {
        String url = "/mobile/brokerages/akd/?brokerage="
                + URLEncoder.encode(brokerage, StandardCharsets.UTF_8)
                + "&start=" + URLEncoder.encode(start, StandardCharsets.UTF_8)
                + "&end=" + URLEncoder.encode(end, StandardCharsets.UTF_8);
        String body = getWithToken(url, config.getBrokerageToken());
        return objectMapper.readValue(body, FintablesBrokerageAkdDetailDto.class);
    }

    /**
     * Hisse bazlı emir defteri (orderbook) işlem verilerini Fintables'ten getirir.
     * Orderbook endpoint'i ayrı bir JWT token gerektirir ({@code fintables.api.orderbook-token}).
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return paginated orderbook response
     * @throws IllegalArgumentException stockCode boş ise
     * @throws Exception                API çağrısı başarısız olursa
     */
    public FintablesOrderbookResponseDto getOrderbook(String stockCode) throws Exception {
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("stockCode boş olamaz");
        }
        String url = "/mobile/orderbook/transactions/?code="
                + URLEncoder.encode(stockCode, StandardCharsets.UTF_8);
        String body = getWithToken(url, config.getOrderbookToken());
        return objectMapper.readValue(body, FintablesOrderbookResponseDto.class);
    }

    /**
     * Belirtilen URL'ye ozel bir Bearer token ile GET istegi yapar.
     *
     * @param url   API URL'i (path)
     * @param token kullanilacak Bearer token
     * @return ham JSON response body string'i
     * @throws RuntimeException HTTP status 200 degilse
     * @throws Exception        baglanti veya I/O hatasi durumunda
     */
    private String getWithToken(String url, String token) throws Exception {
        String fullUrl = config.getBaseUrl() + url;

        // SSRF korunma: sadece konfigure edilen domain'e istek yapilabilir
        URI baseUri = URI.create(config.getBaseUrl());
        URI requestUri = URI.create(fullUrl);
        if (!requestUri.getHost().equals(baseUri.getHost())) {
            throw new IllegalArgumentException("Gecersiz API URL: beklenmeyen domain");
        }

        log.debug("Fintables API GET (custom token): {}", fullUrl);

        Request.Builder requestBuilder = new Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("Origin", "https://fintables.com")
                .addHeader("Referer", "https://fintables.com/")
                .addHeader("Accept", "application/json, text/plain, */*");

        if (token != null && !token.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + token);
        }

        // Cookie header (Cloudflare uyumlu, get() ile tutarli)
        String cookie = config.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookie);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            if (statusCode != 200) {
                log.error("Fintables API hatasi: status={}, url={}", statusCode, fullUrl);
                throw new RuntimeException("Fintables API hatasi: " + statusCode);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }
}
