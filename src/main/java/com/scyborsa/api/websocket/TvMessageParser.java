package com.scyborsa.api.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.model.chart.CandleBar;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingView WebSocket mesaj parser'i.
 * TradingView'in ozel wire protokolunu (~m~ frame formati) parse eder ve
 * bar (mum), quote (fiyat teklifi) ve kontrol mesajlarini cikarir.
 *
 * <p>Protokol formati: {@code ~m~<length>~m~<payload>} seklinde frame'lerden olusur.</p>
 */
@Slf4j
public class TvMessageParser {

    /** JSON parse icin paylasimli ObjectMapper instance'i. */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private TvMessageParser() {}

    /**
     * Ham TradingView mesajini frame'lere ayirir.
     * Her frame {@code ~m~<length>~m~<payload>} formatindadir.
     *
     * @param rawMessage ham WebSocket mesaji
     * @return ayristirilmis frame listesi; bos veya null mesaj icin bos liste
     */
    public static List<String> splitFrames(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> frames = new ArrayList<>();
        int pos = 0;
        while (pos < rawMessage.length()) {
            if (!rawMessage.startsWith("~m~", pos)) break;
            pos += 3;
            int delim = rawMessage.indexOf("~m~", pos);
            if (delim < 0) break;
            int length;
            try {
                length = Integer.parseInt(rawMessage.substring(pos, delim));
            } catch (NumberFormatException e) {
                break;
            }
            pos = delim + 3;
            if (pos + length <= rawMessage.length()) {
                frames.add(rawMessage.substring(pos, pos + length));
                pos += length;
            } else {
                break;
            }
        }
        return frames;
    }

    /**
     * Frame'in heartbeat mesaji olup olmadigini kontrol eder.
     * Heartbeat frame'leri "~h~" ile baslar.
     *
     * @param frame kontrol edilecek frame
     * @return heartbeat ise {@code true}
     */
    public static boolean isHeartbeat(String frame) {
        return frame != null && frame.startsWith("~h~");
    }

    /**
     * JSON frame'inden mesaj tipini ("m" alani) cikarir.
     * Ornegin: "timescale_update", "du", "qsd", "symbol_resolved" vb.
     *
     * @param frame JSON formatinda TradingView frame'i
     * @return mesaj tipi string'i; JSON degilse veya "m" alani yoksa {@code null}
     */
    public static String getMessageType(String frame) {
        try {
            JsonNode node = objectMapper.readTree(frame);
            if (node.has("m")) {
                return node.get("m").asText();
            }
        } catch (Exception e) {
            // Not JSON or no "m" field
        }
        return null;
    }

    /**
     * Frame'den chart session ID'sini cikarir.
     * Session ID, JSON "p" dizisinin ilk elemanidir.
     *
     * @param frame JSON formatinda TradingView frame'i
     * @return chart session ID; bulunamazsa {@code null}
     */
    public static String getChartSessionId(String frame) {
        try {
            JsonNode node = objectMapper.readTree(frame);
            JsonNode p = node.get("p");
            if (p != null && p.isArray() && p.size() > 0) {
                return p.get(0).asText();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * TradingView mesajindan bar (mum) verilerini parse eder.
     * Her bar timestamp, open, high, low, close ve volume degerlerini icerir.
     *
     * @param frame timescale_update veya du tipinde JSON frame
     * @return parse edilen {@link CandleBar} listesi; parse edilemezse bos liste
     */
    public static List<CandleBar> parseBarsFromMessage(String frame) {
        List<CandleBar> bars = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(frame);
            JsonNode p = node.get("p");
            if (p == null || !p.isArray() || p.size() < 2) {
                return bars;
            }
            JsonNode data = p.get(1);
            if (data == null || !data.isObject()) {
                return bars;
            }
            var fieldNames = data.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                JsonNode series = data.get(key);
                if (series == null || !series.isObject()) continue;
                JsonNode sArray = series.get("s");
                if (sArray == null || !sArray.isArray()) continue;
                for (JsonNode barNode : sArray) {
                    JsonNode v = barNode.get("v");
                    if (v == null || !v.isArray() || v.size() < 6) continue;
                    CandleBar bar = new CandleBar(
                            v.get(0).asLong(),
                            v.get(1).asDouble(),
                            v.get(2).asDouble(),
                            v.get(3).asDouble(),
                            v.get(4).asDouble(),
                            v.get(5).asLong()
                    );
                    bars.add(bar);
                }
                if (!bars.isEmpty()) break;
            }
        } catch (Exception e) {
            log.warn("Bar parse hatası: {}", e.getMessage());
        }
        return bars;
    }

    /**
     * symbol_resolved mesajindan sembol adini cikarir.
     * Oncelikle "short_name", yoksa "name" alanini kullanir.
     *
     * @param frame symbol_resolved tipinde JSON frame
     * @return sembol adi; bulunamazsa {@code null}
     */
    public static String parseSymbolFromResolved(String frame) {
        try {
            JsonNode node = objectMapper.readTree(frame);
            JsonNode p = node.get("p");
            if (p != null && p.isArray() && p.size() >= 3) {
                JsonNode resolved = p.get(2);
                if (resolved.has("short_name")) {
                    return resolved.get("short_name").asText();
                }
                if (resolved.has("name")) {
                    return resolved.get("name").asText();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * QSD (Quote Session Data) mesajindan fiyat teklifi verilerini parse eder.
     * Donen map sembol adi, son fiyat (lp), degisim (ch, chp), OHLC fiyatlari,
     * hacim ve son fiyat zamani gibi alanlari icerir.
     *
     * @param frame qsd tipinde JSON frame
     * @return fiyat verilerini iceren map; parse edilemez veya status "ok" degilse {@code null}
     */
    public static Map<String, Object> parseQsdData(String frame) {
        try {
            JsonNode node = objectMapper.readTree(frame);
            JsonNode p = node.get("p");
            if (p == null || !p.isArray() || p.size() < 2) return null;
            JsonNode quoteObj = p.get(1);
            if (quoteObj == null || !quoteObj.isObject()) return null;
            String status = quoteObj.has("s") ? quoteObj.get("s").asText() : null;
            if (!"ok".equals(status)) return null;
            String symbolName = quoteObj.has("n") ? quoteObj.get("n").asText() : null;
            JsonNode v = quoteObj.get("v");
            if (v == null || !v.isObject() || symbolName == null) return null;
            Map<String, Object> result = new HashMap<>();
            result.put("symbol", symbolName);
            String[] doubleFields = {"lp", "ch", "chp", "open_price", "high_price", "low_price", "prev_close_price"};
            for (String field : doubleFields) {
                if (v.has(field) && !v.get(field).isNull()) {
                    result.put(field, v.get(field).asDouble());
                }
            }
            if (v.has("volume") && !v.get("volume").isNull()) {
                result.put("volume", v.get("volume").asLong());
            }
            if (v.has("lp_time") && !v.get("lp_time").isNull()) {
                result.put("lp_time", v.get("lp_time").asLong());
            }
            return result;
        } catch (Exception e) {
            log.debug("QSD parse hatası: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JSON mesajini TradingView wire formatina sarar.
     * Format: {@code ~m~<length>~m~<json>}
     *
     * @param json sarmalanacak JSON string
     * @return TradingView wire formatinda mesaj
     */
    public static String wrapMessage(String json) {
        return "~m~" + json.length() + "~m~" + json;
    }
}
