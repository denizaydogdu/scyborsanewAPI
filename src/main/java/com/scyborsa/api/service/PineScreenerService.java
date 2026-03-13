package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.screener.TvScreenerResponseModel;
import com.scyborsa.api.enums.PeriodsEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Pine Screener verilerini Velzon API üzerinden ceken ve birlestiren servis.
 *
 * <p>Bir hisse icin iki farkli API endpoint'inden veri toplar:</p>
 * <ol>
 *   <li>{@code velzon_indicator_packages} - 6 periyotlu (15dk, 30dk, 1s, 4s, günlük, haftalik) analiz sinyalleri</li>
 *   <li>{@code ALL_STOCKS_WITH_INDICATORS} - Zamansiz (NO_TIME) teknik göstergeler (RSI, MACD, ADX vb.)</li>
 * </ol>
 *
 * <p>Elde edilen veriler UI tarafinin beklentisine uygun {@code d[]} array formatina dönüstürülür.</p>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link VelzonApiClient} - Velzon REST API istemcisi</li>
 *   <li>{@link com.fasterxml.jackson.databind.ObjectMapper} - JSON parse islemi</li>
 * </ul>
 *
 * @see com.scyborsa.api.dto.screener.TvScreenerResponseModel
 * @see com.scyborsa.api.enums.PeriodsEnum
 */
@Slf4j
@Service
public class PineScreenerService {

    /** Velzon API periyot anahtari -> PeriodsEnum esleme haritasi. */
    private static final Map<String, PeriodsEnum> PERIOD_KEY_MAP = Map.of(
            "15", PeriodsEnum.FIFTEEN_MINUTES,
            "30", PeriodsEnum.THIRTY_MINUTES,
            "60", PeriodsEnum.ONE_HOUR,
            "240", PeriodsEnum.FOUR_HOURS,
            "1D", PeriodsEnum.DAILY,
            "1W", PeriodsEnum.WEEK
    );

    /** Velzon API istemcisi. */
    private final VelzonApiClient velzonApiClient;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param velzonApiClient Velzon REST API istemcisi
     * @param objectMapper    JSON parse icin Jackson ObjectMapper
     */
    public PineScreenerService(VelzonApiClient velzonApiClient, ObjectMapper objectMapper) {
        this.velzonApiClient = velzonApiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Bir hisse icin tüm periyotlarda Pine Screener verilerini toplar.
     *
     * <p>Iki API cagrisi yapar:</p>
     * <ol>
     *   <li>{@code velzon_indicator_packages} - 6 periyot analiz sinyalleri</li>
     *   <li>{@code ALL_STOCKS_WITH_INDICATORS} - NO_TIME teknik göstergeler</li>
     * </ol>
     * <p>Ek olarak NO_TIME_CHART icin bos bir response ekler (gauge devre disi).</p>
     *
     * @param stockId       hisse sembolü (ör. "THYAO")
     * @param indicatorName indikator adi (su an kullanilmiyor, ileride filtreleme icin)
     * @return tüm periyotlara ait screener response listesi (8 eleman: 6 periyot + NO_TIME + NO_TIME_CHART)
     */
    public List<TvScreenerResponseModel> getPineScreenerDataForStock(String stockId, String indicatorName) {
        List<TvScreenerResponseModel> allResponses = new ArrayList<>();

        // 1. velzon_indicator_packages — 6 periyot analiz sinyalleri
        try {
            String path = "/api/pinescreener/velzon_indicator_packages/" + stockId;
            String responseBody = velzonApiClient.get(path);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.get("data");

            if (dataNode != null) {
                for (Map.Entry<String, PeriodsEnum> entry : PERIOD_KEY_MAP.entrySet()) {
                    String periodKey = entry.getKey();
                    PeriodsEnum periodsEnum = entry.getValue();

                    JsonNode periodData = dataNode.get(periodKey);
                    if (periodData != null) {
                        var model = buildIndicatorPackageResponse(periodData, stockId, periodsEnum);
                        allResponses.add(model);
                    } else {
                        log.warn("velzon_indicator_packages: {} periyodu bulunamadi, stockId={}", periodKey, stockId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("velzon_indicator_packages API cagrisi basarisiz: stockId={}", stockId, e);
        }

        // 2. ALL_STOCKS_WITH_INDICATORS — NO_TIME teknik gostergeler
        try {
            String path = "/api/screener/ALL_STOCKS_WITH_INDICATORS/BIST:" + stockId;
            String responseBody = velzonApiClient.get(path);
            JsonNode root = objectMapper.readTree(responseBody);

            var noTimeModel = buildNoTimeResponse(root, stockId);
            noTimeModel.setPeriodsEnum(PeriodsEnum.NO_TIME);
            noTimeModel.setScreenerName(PeriodsEnum.NO_TIME.getName());
            allResponses.add(noTimeModel);
        } catch (Exception e) {
            log.error("ALL_STOCKS_WITH_INDICATORS API cagrisi basarisiz: stockId={}", stockId, e);
        }

        // 3. NO_TIME_CHART — Gauge devre disi, bos response
        var chartResponse = new TvScreenerResponseModel();
        chartResponse.setPeriodsEnum(PeriodsEnum.NO_TIME_CHART);
        chartResponse.setScreenerName(PeriodsEnum.NO_TIME_CHART.getName());
        chartResponse.setTechnicalResponseDtoList(new ArrayList<>());
        allResponses.add(chartResponse);

        return allResponses;
    }

    /**
     * velzon_indicator_packages API yaniti → TvScreenerResponseModel
     * plotN degerlerini d[] array'e map'ler (UI uyumlulugu icin).
     *
     * d[6]=plot6(T3), d[7]=plot7(SuperTrend), ..., d[17]=plot17(MadridRibbon)
     */
    private TvScreenerResponseModel buildIndicatorPackageResponse(JsonNode periodData, String stockId, PeriodsEnum periodsEnum) {
        var model = new TvScreenerResponseModel();
        model.setPeriodsEnum(periodsEnum);
        model.setScreenerName(periodsEnum.getName());
        model.setTotalCount(1);

        var dataItem = new TvScreenerResponseModel.DataItem();
        dataItem.setS("BIST:" + stockId);

        // d[] array: index 0-5 bos (metadata), 6-17 = plot6-plot17
        List<Object> d = new ArrayList<>(Collections.nCopies(18, null));
        for (int plotIndex = 6; plotIndex <= 17; plotIndex++) {
            String plotKey = "plot" + plotIndex;
            JsonNode plotNode = periodData.get(plotKey);
            if (plotNode != null && !plotNode.isNull()) {
                d.set(plotIndex, plotNode.isNumber() ? plotNode.numberValue() : plotNode.asInt());
            }
        }
        dataItem.setD(d);

        model.setData(List.of(dataItem));
        return model;
    }

    /**
     * ALL_STOCKS_WITH_INDICATORS API yaniti → NO_TIME TvScreenerResponseModel
     * rawData (131 eleman) → d[] array (24 eleman, UI uyumlulugu icin).
     *
     * Mapping:
     * d[6]  = close     → rawData[6]
     * d[12] = RSI       → rawData[31]
     * d[13] = MACD.macd → rawData[59]
     * d[14] = MACD.signal → rawData[61]
     * d[15] = Momentum  → rawData[45]
     * d[16] = ADX       → rawData[53]
     * d[17] = EMA20     → rawData[77]
     * d[18] = EMA50     → rawData[81]
     * d[19] = SMA50     → rawData[93]
     * d[20] = SMA200    → rawData[95]
     * d[21] = Ichimoku.CLine → rawData[69]
     * d[22] = Ichimoku.BLine → rawData[67]
     * d[23] = HullMA9   → rawData[97]
     */
    private TvScreenerResponseModel buildNoTimeResponse(JsonNode root, String stockId) {
        var model = new TvScreenerResponseModel();

        var dataItem = new TvScreenerResponseModel.DataItem();
        dataItem.setS("BIST:" + stockId);

        // rawData cek
        List<Object> rawData = extractRawData(root);

        // d[] array olustur (24 eleman)
        List<Object> d = new ArrayList<>(Collections.nCopies(24, null));

        if (rawData != null && rawData.size() >= 98) {
            d.set(6, rawData.get(6));     // close
            d.set(12, rawData.get(31));   // RSI
            d.set(13, rawData.get(59));   // MACD.macd
            d.set(14, rawData.get(61));   // MACD.signal
            d.set(15, rawData.get(45));   // Momentum
            d.set(16, rawData.get(53));   // ADX
            d.set(17, rawData.get(77));   // EMA20
            d.set(18, rawData.get(81));   // EMA50
            d.set(19, rawData.get(93));   // SMA50
            d.set(20, rawData.get(95));   // SMA200
            d.set(21, rawData.get(69));   // Ichimoku.CLine
            d.set(22, rawData.get(67));   // Ichimoku.BLine
            d.set(23, rawData.get(97));   // HullMA9
        } else {
            log.warn("rawData yetersiz: size={}, stockId={}", rawData != null ? rawData.size() : 0, stockId);
        }

        dataItem.setD(d);
        model.setData(List.of(dataItem));
        model.setTotalCount(1);
        return model;
    }

    /**
     * ALL_STOCKS_WITH_INDICATORS response'undan rawData array'ini cikarir.
     * Format: { "data": { "additionalData": { "rawData": [...] } } }
     */
    private List<Object> extractRawData(JsonNode root) {
        try {
            JsonNode dataNode = root.path("data").path("additionalData").path("rawData");
            if (dataNode.isMissingNode() || !dataNode.isArray()) {
                log.warn("rawData bulunamadi veya array degil");
                return null;
            }

            List<Object> rawData = new ArrayList<>();
            for (JsonNode node : dataNode) {
                if (node.isNull()) {
                    rawData.add(null);
                } else if (node.isNumber()) {
                    rawData.add(node.numberValue());
                } else if (node.isTextual()) {
                    rawData.add(node.asText());
                } else if (node.isBoolean()) {
                    rawData.add(node.asBoolean());
                } else {
                    rawData.add(node.toString());
                }
            }
            return rawData;
        } catch (Exception e) {
            log.error("rawData parse hatasi", e);
            return null;
        }
    }
}
