package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.KapMcpHaberDto;
import com.scyborsa.api.service.kap.KapMcpService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * KAP MCP haber REST controller'ı.
 *
 * <p>Fintables MCP üzerinden KAP bildirimlerine erişim sağlar.
 * Hisse bazlı arama, detay içerik ve ek erişimi endpoint'leri sunar.</p>
 *
 * @see KapMcpService
 * @see KapMcpHaberDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/kap-mcp")
@RequiredArgsConstructor
public class KapMcpController {

    /** KAP MCP servis katmanı. */
    private final KapMcpService kapMcpService;

    /**
     * Belirtilen hisse için KAP haberlerini MCP üzerinden arar.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @param limit     maksimum sonuç sayısı (varsayılan: 10)
     * @return KAP haber DTO listesi
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<KapMcpHaberDto>> searchKapHaberleri(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode,
            @RequestParam(defaultValue = "10") int limit) {

        List<KapMcpHaberDto> result = kapMcpService.searchKapHaberleri(stockCode, limit);
        return ResponseEntity.ok(result);
    }

    /**
     * Chunk ID'leri ile haber detay içeriğini getirir.
     *
     * @param ids virgülle ayrılmış chunk ID'leri (ör: "id1,id2,id3")
     * @return birleştirilmiş chunk metin içeriği
     */
    @GetMapping("/detay")
    public ResponseEntity<String> getHaberDetay(@RequestParam String ids) {
        if (ids == null || ids.isBlank()) {
            return ResponseEntity.badRequest().body("ids parametresi gerekli");
        }

        List<String> chunkIds = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (chunkIds.size() > 50) {
            return ResponseEntity.badRequest().body("Maksimum 50 chunk ID gönderilebilir");
        }
        // Chunk ID uzunluk kontrolü
        for (String id : chunkIds) {
            if (id.trim().length() > 100) {
                return ResponseEntity.badRequest().body("Geçersiz chunk ID formatı");
            }
        }
        String result = kapMcpService.getHaberDetay(chunkIds);
        return ResponseEntity.ok(result);
    }

    /**
     * KAP bildirimine ait eklerin chunk ID'lerini getirir.
     *
     * @param kapBildirimId KAP bildirim ID'si
     * @return ek chunk ID listesi
     */
    @GetMapping("/ek/{kapBildirimId}")
    public ResponseEntity<List<String>> getHaberEkleri(@PathVariable Long kapBildirimId) {
        List<String> result = kapMcpService.getHaberEkleri(kapBildirimId);
        return ResponseEntity.ok(result);
    }
}
