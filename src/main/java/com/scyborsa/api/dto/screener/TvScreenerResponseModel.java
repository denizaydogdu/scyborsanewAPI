package com.scyborsa.api.dto.screener;

import com.scyborsa.api.dto.TechnicalResponseDto;
import com.scyborsa.api.enums.PeriodsEnum;
import lombok.Data;

import java.util.List;

/**
 * TradingView screener API yanit modeli.
 * <p>
 * TradingView'in screener endpoint'inden donen ham veriyi parse etmek icin kullanilir.
 * Screener sonuclari bu modele deserialize edilir, ardindan teknik analiz DTO'larina
 * donusturulur.
 * </p>
 *
 * @see TechnicalResponseDto
 */
@Data
public class TvScreenerResponseModel {

    /** Screener sorgusundan donen toplam sonuc sayisi. */
    private Integer totalCount;

    /** Screener'dan donen ham veri satirlari. Her satir bir hisseyi temsil eder. */
    private List<DataItem> data;

    /** Screener adi/turu (or. "turkey" piyasa filtresi). */
    private String screenerName;

    /** Sorgunun yapildigi zaman periyodu. */
    private PeriodsEnum periodsEnum;

    /** Parse edilmis teknik analiz yanitlari listesi. Her eleman bir sembol-periyot ciftidir. */
    private List<TechnicalResponseDto> technicalResponseDtoList;

    /**
     * TradingView screener'dan donen tek bir veri satiri.
     * <p>
     * Her satir bir hissenin screener verilerini icerir.
     * </p>
     */
    @Data
    public static class DataItem {

        /** Sembol adi (or. "BIST:THYAO"). TradingView formatinda gelir. */
        private String s;

        /** Sembolun screener degerleri. Siralama, screener sorgusundaki kolon sirasina gore gelir. */
        private List<Object> d;
    }
}
