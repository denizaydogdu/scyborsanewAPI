package com.scyborsa.api.model;

import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Zenginleştirme cache entity'si.
 *
 * <p>AKD, Takas, Orderbook ve AI yorum verilerini günlük olarak saklar. Tek tablo, {@code data_type}
 * ile AKD/TAKAS/ORDERBOOK/AI_COMMENT ayrımı yapılır. AI_COMMENT tipinde {@code jsonData} düz metin içerir.</p>
 *
 * <p>Unique constraint: {@code (stock_code, cache_date, data_type)} →
 * aynı hisse+gün+tip kombinasyonu tekrar yazılmaz.</p>
 */
@Entity
@Table(name = "enrichment_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_enrichment_cache_code_date_type",
                columnNames = {"stock_code", "cache_date", "data_type"}),
        indexes = @Index(
                name = "idx_enrichment_cache_date_type",
                columnList = "cache_date, data_type"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrichmentCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hisse kodu (ör: "GARAN"). */
    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    /** Verinin ait olduğu tarih. */
    @Column(name = "cache_date", nullable = false)
    private LocalDate cacheDate;

    /** Veri tipi (AKD, TAKAS, ORDERBOOK veya AI_COMMENT). */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private EnrichmentDataTypeEnum dataType;

    /** Zenginleştirilmiş veri (AKD/TAKAS/ORDERBOOK: JSON DTO, AI_COMMENT: düz metin). */
    @Column(name = "json_data", nullable = false, columnDefinition = "TEXT")
    private String jsonData;

    /** Kaydın oluşturulma zamanı. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /**
     * Persist öncesi otomatik zaman damgası atar.
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }
}
