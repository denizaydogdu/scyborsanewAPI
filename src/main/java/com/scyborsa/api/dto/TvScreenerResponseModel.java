package com.scyborsa.api.dto;

import com.scyborsa.api.enums.PeriodsEnum;
import lombok.Data;

import java.util.List;

/**
 * TradingView screener API yanıt modeli.
 * <p>
 * TradingView'ın screener endpoint'inden dönen ham veriyi parse etmek için kullanılır.
 * Screener sonuçları bu modele deserialize edilir, ardından teknik analiz DTO'larına
 * dönüştürülür.
 * </p>
 *
 * @see TechnicalResponseDto
 */
@Data
public class TvScreenerResponseModel {

    /** Screener sorgusundan dönen toplam sonuç sayısı. */
    private Integer totalCount;

    /** Screener'dan dönen ham veri satırları. Her satır bir hisseyi temsil eder. */
    private List<DataItem> data;

    /** Screener adı/türü (ör. "turkey" piyasa filtresi). */
    private String screenerName;

    /** Sorgunun yapıldığı zaman periyodu. */
    private PeriodsEnum periodsEnum;

    /** Parse edilmiş teknik analiz yanıtları listesi. Her eleman bir sembol-periyot çiftidir. */
    private List<TechnicalResponseDto> technicalResponseDtoList;

    /**
     * TradingView screener'dan dönen tek bir veri satırı.
     * <p>
     * Her satır bir hissenin screener verilerini içerir.
     * </p>
     */
    @Data
    public static class DataItem {

        /** Sembol adı (ör. "BIST:THYAO"). TradingView formatında gelir. */
        private String s;

        /** Sembolün screener değerleri. Sıralama, screener sorgusundaki kolon sırasına göre gelir. */
        private List<Object> d;
    }
}
