package com.scyborsa.api.enums;

/**
 * Zenginleştirme cache veri tipi enum'ı.
 *
 * <p>{@code enrichment_cache} tablosunda hangi tip verinin saklandığını belirtir.</p>
 */
public enum EnrichmentDataTypeEnum {

    /** Aracı Kurum Dağılımı verisi. */
    AKD,

    /** Takas (Saklama Dağılımı) verisi. */
    TAKAS,

    /** Emir Defteri (Orderbook) verisi. */
    ORDERBOOK,

    /** AI teknik analiz yorumu. */
    AI_COMMENT,

    /** Günlük açığa satış istatistikleri (Fintables MCP). */
    ACIGA_SATIS,

    /** VBTS tedbirli hisseler (Fintables MCP). */
    VBTS_TEDBIR,

    /** Analist hedef fiyat ve tahminleri (Fintables MCP). */
    HEDEF_FIYAT,

    /** Şirket guidance/beklentileri (Fintables MCP). */
    GUIDANCE,

    /** Halka arz verileri (Fintables MCP). */
    HALKA_ARZ,

    /** Finansal oranlar — F/K, PD/DD, ROE vb. (Fintables MCP). */
    FINANSAL_ORAN,

    /** Fon portföy dağılım raporları (Fintables MCP). */
    FON_PORTFOY,

    /** Bilanço + gelir tablosu + nakit akım verileri (Fintables MCP). */
    FINANSAL_TABLO
}
