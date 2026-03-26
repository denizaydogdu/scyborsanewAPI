package com.scyborsa.api.enums;

/**
 * Fiyat alarmi yon belirleyicisi.
 *
 * <p>Kullanicinin belirlediği hedef fiyata yukaridan mi asagidan mi
 * ulasildigi kontrol edilecegini tanimlar.</p>
 *
 * @see com.scyborsa.api.model.PriceAlert
 */
public enum AlertDirection {

    /** Fiyat hedef degerin uzerine ciktiginda tetiklenir. */
    ABOVE,

    /** Fiyat hedef degerin altina dustugunde tetiklenir. */
    BELOW
}
