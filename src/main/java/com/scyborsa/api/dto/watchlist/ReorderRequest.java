package com.scyborsa.api.dto.watchlist;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Siralama degistirme istegi DTO'su.
 *
 * <p>Takip listelerinin veya listedeki hisselerin sira degisikligi icin
 * kullanilir. {@code itemIds} listesi yeni siralamaya gore ID'leri icerir.</p>
 */
@Data
public class ReorderRequest {

    /** Yeni siralamaya gore item ID listesi. Zorunlu. */
    @NotNull
    private List<Long> itemIds;
}
