package com.scyborsa.api.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * Takip hissesi resim yükleme, okuma ve silme servisi.
 *
 * <p>Resimleri disk üzerinde belirtilen dizine kaydeder.
 * Maksimum 5MB dosya boyutu ve png/jpg/jpeg/webp uzantıları desteklenir.</p>
 *
 * @see TakipHissesiService#updateResimUrl(Long, String)
 */
@Slf4j
@Service
public class TakipHissesiImageService {

    /** Resim yükleme dizini. */
    @Value("${takip-hissesi.image.upload-dir:./uploads/takip-hisseleri}")
    private String uploadDir;

    /** Maksimum dosya boyutu: 5 MB. */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /** İzin verilen dosya uzantıları. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    /**
     * Yükleme dizinini oluşturur (yoksa).
     */
    @PostConstruct
    protected void init() {
        try {
            Files.createDirectories(Paths.get(uploadDir));
            log.info("[TAKIP-HISSESI-IMAGE] Yükleme dizini hazır: {}", uploadDir);
        } catch (IOException e) {
            log.error("[TAKIP-HISSESI-IMAGE] Yükleme dizini oluşturulamadı: {}", uploadDir, e);
        }
    }

    /**
     * Resim dosyasını diske kaydeder.
     *
     * @param id   takip hissesi ID'si (dosya adı prefixi)
     * @param file yüklenen dosya
     * @return kaydedilen dosya adı ({id}_{timestamp}.{ext})
     * @throws IllegalArgumentException dosya boyutu veya uzantısı geçersizse
     * @throws RuntimeException         disk yazma hatası
     */
    public String saveImage(Long id, MultipartFile file) {
        // Boyut kontrolü
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Dosya boyutu 5MB'dan büyük olamaz");
        }

        // Uzantı kontrolü
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new IllegalArgumentException("Desteklenmeyen dosya formatı: " + extension
                    + ". İzin verilen: " + ALLOWED_EXTENSIONS);
        }

        // Dosya adı oluştur
        String filename = id + "_" + System.currentTimeMillis() + "." + extension.toLowerCase();
        Path targetPath = Paths.get(uploadDir, filename);

        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[TAKIP-HISSESI-IMAGE] Resim kaydedildi: {}", filename);
            return filename;
        } catch (IOException e) {
            log.error("[TAKIP-HISSESI-IMAGE] Resim kaydetme hatası: {}", filename, e);
            throw new RuntimeException("Resim kaydedilemedi", e);
        }
    }

    /**
     * Disk üzerinden resim dosyasını okur.
     *
     * @param filename dosya adı
     * @return dosya byte dizisi veya {@code null} (dosya bulunamazsa)
     */
    public byte[] getImage(String filename) {
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            log.warn("[TAKIP-HISSESI-IMAGE] Path traversal girişimi engellendi: {}", filename);
            return null;
        }
        Path filePath = Paths.get(uploadDir, filename).normalize();
        if (!filePath.startsWith(Paths.get(uploadDir).normalize())) {
            log.warn("[TAKIP-HISSESI-IMAGE] Path traversal girişimi engellendi: {}", filename);
            return null;
        }
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("[TAKIP-HISSESI-IMAGE] Resim okuma hatası: {}", filename, e);
            return null;
        }
    }

    /**
     * Disk üzerinden resim dosyasını siler.
     *
     * @param filename silinecek dosya adı
     */
    public void deleteImage(String filename) {
        if (filename == null || filename.isBlank()) return;
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            log.warn("[TAKIP-HISSESI-IMAGE] Path traversal girişimi engellendi (delete): {}", filename);
            return;
        }
        Path filePath = Paths.get(uploadDir, filename).normalize();
        try {
            Files.deleteIfExists(filePath);
            log.info("[TAKIP-HISSESI-IMAGE] Resim silindi: {}", filename);
        } catch (IOException e) {
            log.error("[TAKIP-HISSESI-IMAGE] Resim silme hatası: {}", filename, e);
        }
    }

    /**
     * Dosya uzantısına göre MediaType döndürür.
     *
     * @param filename dosya adı
     * @return uygun MediaType
     */
    public MediaType getMediaType(String filename) {
        String ext = getExtension(filename).toLowerCase();
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * Dosya adından uzantıyı çıkarır.
     *
     * @param filename dosya adı
     * @return uzantı (noktasız)
     */
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
