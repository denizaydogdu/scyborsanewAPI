package com.scyborsa.api.controller;

import com.scyborsa.api.dto.UserDto;
import com.scyborsa.api.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Kullanici yonetimi REST controller'i.
 *
 * <p>Kullanici CRUD islemlerini yonetir. {@code /api/v1/users} altindaki
 * endpoint'leri sunar.</p>
 *
 * @see com.scyborsa.api.service.UserService
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Tum kullanicilari listeler.
     *
     * @return kullanici listesi
     */
    @GetMapping
    public ResponseEntity<List<UserDto>> getAll() {
        return ResponseEntity.ok(userService.getTumKullanicilar());
    }

    /**
     * E-posta adresine gore kullanici getirir.
     *
     * @param email kullanicinin e-posta adresi
     * @return kullanici bilgileri
     */
    @GetMapping("/by-email")
    public ResponseEntity<UserDto> getByEmail(@RequestParam String email) {
        return ResponseEntity.ok(userService.getByEmail(email));
    }

    /**
     * ID'ye gore kullanici getirir.
     *
     * @param id kullanici ID'si
     * @return kullanici bilgileri
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    /**
     * Yeni kullanici olusturur.
     *
     * @param dto kullanici bilgileri (username, password, role zorunlu)
     * @return olusturulan kullanici (201 Created)
     */
    @PostMapping
    public ResponseEntity<UserDto> create(@RequestBody UserDto dto) {
        UserDto created = userService.kaydet(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Mevcut kullaniciyi gunceller.
     *
     * @param id   kullanici ID'si
     * @param dto  guncellenecek kullanici bilgileri
     * @return guncellenen kullanici
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> update(@PathVariable Long id, @RequestBody UserDto dto) {
        dto.setId(id);
        return ResponseEntity.ok(userService.kaydet(dto));
    }

    /**
     * Kullanici profil bilgilerini gunceller (kisitli guncelleme).
     *
     * <p>Sadece ad soyad ve opsiyonel olarak sifre guncellenir.
     * Rol, aktiflik, gecerlilik tarihleri gibi yonetimsel alanlar
     * bu endpoint uzerinden degistirilemez.</p>
     *
     * @param id   kullanici ID'si
     * @param body adSoyad (zorunlu) ve password (opsiyonel) iceren request body
     * @return guncellenen kullanici bilgileri
     */
    @PutMapping("/{id}/profil")
    public ResponseEntity<UserDto> updateProfil(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String adSoyad = body.get("adSoyad");
        String password = body.get("password");
        String telegramUsername = body.get("telegramUsername");
        String phoneNumber = body.get("phoneNumber");
        return ResponseEntity.ok(userService.updateProfil(id, adSoyad, password, telegramUsername, phoneNumber));
    }

    /**
     * Kullanicinin aktif/pasif durumunu tersine cevirir.
     *
     * @param id kullanici ID'si
     * @return 204 No Content
     */
    @PatchMapping("/{id}/aktif")
    public ResponseEntity<Void> toggleAktif(@PathVariable Long id) {
        userService.aktifToggle(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * RuntimeException'lari HTTP 400 Bad Request olarak dondurur.
     *
     * <p>UserService.kaydet() icindeki validation hatalari (email/username duplicate,
     * sifre zorunlulugu vb.) bu handler tarafindan yakalanir ve spesifik hata mesaji
     * ile birlikte 400 status code dondurulur.</p>
     *
     * @param ex yakalanan RuntimeException
     * @return 400 Bad Request + hata mesaji
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
