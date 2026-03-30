package com.scyborsa.api.service;

import com.scyborsa.api.dto.UserDto;
import com.scyborsa.api.dto.auth.LoginHistoryDto;
import com.scyborsa.api.dto.auth.LoginRequestDto;
import com.scyborsa.api.dto.auth.LoginResponseDto;
import com.scyborsa.api.enums.UserGroupEnum;
import com.scyborsa.api.enums.UserRoleEnum;
import com.scyborsa.api.model.AppUser;
import com.scyborsa.api.model.LoginHistory;
import com.scyborsa.api.repository.AppUserRepository;
import com.scyborsa.api.repository.LoginHistoryRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Kullanici islemlerini yoneten servis sinifi.
 *
 * <p>Giris dogrulama, kullanici CRUD islemleri ve admin seed
 * islemlerini icerir. Sifre hashleme icin {@link BCryptPasswordEncoder}
 * kullanilir.</p>
 *
 * @see com.scyborsa.api.model.AppUser
 * @see com.scyborsa.api.repository.AppUserRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /** Kullanici veritabani erisim katmani. */
    private final AppUserRepository appUserRepository;

    /** Giris gecmisi veritabani erisim katmani. */
    private final LoginHistoryRepository loginHistoryRepository;

    /** BCrypt sifre encoder'i. Spring Security context'i olmadan dogrudan kullanilir. */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * Kullanici giris dogrulama islemi.
     *
     * <p>E-posta ve sifreyi kontrol eder. Ek olarak aktiflik durumu
     * ve gecerlilik tarihlerini de dogrular.</p>
     *
     * @param request giris istegi (email + password)
     * @return giris yaniti (basari/basarisizlik bilgisi)
     */
    public LoginResponseDto authenticate(LoginRequestDto request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return fail("Hatali e-posta veya sifre");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            return fail("Hatali e-posta veya sifre");
        }

        var userOpt = appUserRepository.findByEmail(request.getEmail().trim().toLowerCase());

        if (userOpt.isEmpty()) {
            log.warn("Giris denemesi basarisiz — kullanici bulunamadi: {}", request.getEmail());
            String email = request.getEmail().trim().toLowerCase();
            String prefix = "UNKNOWN_USER:";
            int maxEmailLen = 100 - prefix.length();
            String reason = prefix + (email.length() > maxEmailLen ? email.substring(0, maxEmailLen) : email);
            recordLoginHistory(null, request, false, reason);
            return fail("Hatali e-posta veya sifre");
        }

        AppUser user = userOpt.get();

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Giris denemesi basarisiz — yanlis sifre: {}", request.getEmail());
            recordLoginHistory(user, request, false, "BAD_CREDENTIALS");
            return fail("Hatali e-posta veya sifre");
        }

        if (!Boolean.TRUE.equals(user.getAktif())) {
            log.warn("Giris denemesi basarisiz — kullanici pasif: {}", request.getEmail());
            recordLoginHistory(user, request, false, "DISABLED");
            return fail("DISABLED");
        }

        LocalDate today = LocalDate.now();

        if (user.getValidTo() != null && user.getValidTo().isBefore(today)) {
            log.warn("Giris denemesi basarisiz — erisim suresi dolmus: {}", request.getEmail());
            recordLoginHistory(user, request, false, "EXPIRED");
            return fail("EXPIRED");
        }

        if (user.getValidFrom() != null && user.getValidFrom().isAfter(today)) {
            log.warn("Giris denemesi basarisiz — erisim henuz baslamadi: {}", request.getEmail());
            recordLoginHistory(user, request, false, "NOT_YET_ACTIVE");
            return fail("NOT_YET_ACTIVE");
        }

        log.info("Basarili giris: {} ({})", user.getEmail(), user.getRole());
        recordLoginHistory(user, request, true, null);
        updateLoginSummary(user, request.getIpAddress());

        return LoginResponseDto.builder()
                .success(true)
                .role(user.getRole() != null ? user.getRole().name() : null)
                .username(user.getUsername())
                .email(user.getEmail())
                .adSoyad(user.getAdSoyad())
                .build();
    }

    /**
     * Tum kullanicilari ID sirasina gore getirir.
     *
     * @return kullanici DTO listesi (sifre bilgisi icerilmez)
     */
    public List<UserDto> getTumKullanicilar() {
        return appUserRepository.findAllByOrderByIdAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * ID'ye gore kullanici getirir.
     *
     * @param id kullanici ID'si
     * @return kullanici DTO'su (sifre bilgisi icerilmez)
     * @throws RuntimeException kullanici bulunamazsa
     */
    public UserDto getById(Long id) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + id));
        return toDto(user);
    }

    /**
     * Yeni kullanici olusturur veya mevcut kullaniciyi gunceller.
     *
     * <p>Eger {@code dto.id} {@code null} ise yeni kullanici olusturulur ve sifre
     * BCrypt ile hashlenir. {@code dto.id} mevcutsa guncelleme yapilir; sifre
     * sadece {@code dto.password} dolu ise yeniden hashlenir, bos ise mevcut
     * hash korunur.</p>
     *
     * @param dto kullanici bilgileri
     * @return kaydedilen kullanici DTO'su (sifre bilgisi icerilmez)
     */
    @Transactional
    public UserDto kaydet(UserDto dto) {
        AppUser user;

        // Email normalizasyonu (case-insensitive)
        String emailNorm = dto.getEmail() != null ? dto.getEmail().trim().toLowerCase() : null;

        // Email zorunlu kontrolü
        if (emailNorm == null || emailNorm.isBlank()) {
            throw new RuntimeException("E-posta adresi zorunludur");
        }

        // Tarih sırası kontrolü
        if (dto.getValidFrom() != null && dto.getValidTo() != null
                && dto.getValidFrom().isAfter(dto.getValidTo())) {
            throw new RuntimeException("Hesap başlangıç tarihi bitiş tarihinden sonra olamaz");
        }

        // Normalize edilmis degerler (duplicate check + persist tutarli olmali)
        String normalizedUsername = normalizeBlankToNull(dto.getUsername());
        String normalizedTelegram = normalizeBlankToNull(dto.getTelegramUsername());

        if (dto.getId() == null) {
            // Yeni kullanici — duplicate username kontrol (username opsiyonel)
            if (normalizedUsername != null
                    && appUserRepository.existsByUsername(normalizedUsername)) {
                throw new RuntimeException("Bu kullanici adi zaten mevcut: " + normalizedUsername);
            }
            // Yeni kullanici — duplicate email kontrol
            if (appUserRepository.existsByEmail(emailNorm)) {
                throw new RuntimeException("Bu e-posta adresi zaten mevcut: " + emailNorm);
            }
            // Telegram username duplicate kontrol
            if (normalizedTelegram != null
                    && appUserRepository.existsByTelegramUsernameIgnoreCase(normalizedTelegram)) {
                throw new RuntimeException("Bu Telegram adı zaten kullanılıyor: " + normalizedTelegram);
            }
            if (dto.getPassword() == null || dto.getPassword().isBlank()) {
                throw new RuntimeException("Yeni kullanici icin sifre zorunludur");
            }
            String normalizedPhone = normalizeBlankToNull(dto.getPhoneNumber());
            if (normalizedPhone != null) {
                normalizedPhone = canonicalizeTurkishPhone(normalizedPhone);
                if (normalizedPhone.isEmpty()) normalizedPhone = null;
            }
            user = AppUser.builder()
                    .username(normalizedUsername)
                    .email(emailNorm)
                    .password(passwordEncoder.encode(dto.getPassword()))
                    .adSoyad(dto.getAdSoyad())
                    .role(parseRole(dto.getRole()))
                    .userGroup(parseUserGroup(dto.getUserGroup()))
                    .telegramUsername(normalizedTelegram)
                    .phoneNumber(normalizedPhone)
                    .validFrom(dto.getValidFrom())
                    .validTo(dto.getValidTo())
                    .aktif(dto.getAktif() != null ? dto.getAktif() : true)
                    .build();
        } else {
            // Mevcut kullanici guncelle
            user = appUserRepository.findById(dto.getId())
                    .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + dto.getId()));

            // Username degistiyse duplicate kontrol (username opsiyonel)
            if (normalizedUsername != null
                    && !normalizedUsername.equals(user.getUsername())
                    && appUserRepository.existsByUsername(normalizedUsername)) {
                throw new RuntimeException("Bu kullanici adi zaten mevcut: " + normalizedUsername);
            }

            // Email degistiyse duplicate kontrol
            String storedEmailNorm = user.getEmail() != null ? user.getEmail().trim().toLowerCase() : null;
            if (!emailNorm.equals(storedEmailNorm)
                    && appUserRepository.existsByEmail(emailNorm)) {
                throw new RuntimeException("Bu e-posta adresi zaten mevcut: " + emailNorm);
            }

            // Telegram username degistiyse duplicate kontrol
            if (normalizedTelegram != null
                    && !normalizedTelegram.equalsIgnoreCase(user.getTelegramUsername())
                    && appUserRepository.existsByTelegramUsernameIgnoreCase(normalizedTelegram)) {
                throw new RuntimeException("Bu Telegram adı zaten kullanılıyor: " + normalizedTelegram);
            }

            String normalizedPhoneUpdate = normalizeBlankToNull(dto.getPhoneNumber());
            if (normalizedPhoneUpdate != null) {
                normalizedPhoneUpdate = canonicalizeTurkishPhone(normalizedPhoneUpdate);
                if (normalizedPhoneUpdate.isEmpty()) normalizedPhoneUpdate = null;
            }
            user.setUsername(normalizedUsername);
            user.setEmail(emailNorm);
            user.setAdSoyad(dto.getAdSoyad());
            user.setRole(parseRole(dto.getRole()));
            user.setUserGroup(parseUserGroup(dto.getUserGroup()));
            user.setTelegramUsername(normalizedTelegram);
            user.setPhoneNumber(normalizedPhoneUpdate);
            user.setValidFrom(dto.getValidFrom());
            user.setValidTo(dto.getValidTo());

            if (dto.getAktif() != null) {
                user.setAktif(dto.getAktif());
            }

            // Sifre sadece dolu gonderilirse guncellenir
            if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
                user.setPassword(passwordEncoder.encode(dto.getPassword()));
            }
        }

        AppUser saved = appUserRepository.save(user);
        log.info("Kullanici kaydedildi: {} (ID: {})",
                saved.getEmail() != null ? saved.getEmail() : saved.getUsername(), saved.getId());
        return toDto(saved);
    }

    /**
     * Kullanicinin aktif/pasif durumunu tersine cevirir.
     *
     * @param id kullanici ID'si
     * @throws RuntimeException kullanici bulunamazsa
     */
    @Transactional
    public void aktifToggle(Long id) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + id));
        user.setAktif(!Boolean.TRUE.equals(user.getAktif()));
        appUserRepository.save(user);
        log.info("Kullanici aktiflik degistirildi: {} -> aktif={}",
                user.getEmail() != null ? user.getEmail() : user.getUsername(), user.getAktif());
    }

    /**
     * Uygulama basladiginda admin kullanici yoksa olusturur.
     *
     * <p>Varsayilan admin kullanici: {@code admin / scyborsa2024}</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedAdminUser() {
        if (appUserRepository.existsByEmail("admin@scyborsa.com")) {
            log.info("Admin kullanici zaten mevcut, seed atlaniyor");
            return;
        }

        // Mevcut admin kullanicisi varsa email ata (migration)
        var existingAdmin = appUserRepository.findByUsername("admin");
        if (existingAdmin.isPresent()) {
            AppUser adm = existingAdmin.get();
            if (adm.getEmail() == null) {
                adm.setEmail("admin@scyborsa.com");
                appUserRepository.save(adm);
                log.info("Mevcut admin kullanicisina email atandi: admin@scyborsa.com");
            } else {
                log.info("Mevcut admin kullanicisi farkli bir email ile kayitli: {}", adm.getEmail());
            }
            return;
        }

        AppUser admin = AppUser.builder()
                .username("admin")
                .email("admin@scyborsa.com")
                .password(passwordEncoder.encode("scyborsa2024"))
                .adSoyad("Sistem Yoneticisi")
                .role(UserRoleEnum.ADMIN)
                .aktif(true)
                .build();

        appUserRepository.save(admin);
        log.info("Admin kullanici seed edildi: admin@scyborsa.com");
    }

    /**
     * E-posta adresine gore kullanici getirir.
     *
     * @param email kullanicinin e-posta adresi
     * @return kullanici DTO'su (sifre bilgisi icerilmez)
     * @throws RuntimeException kullanici bulunamazsa
     */
    public UserDto getByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("E-posta adresi zorunludur");
        }
        String emailNorm = email.trim().toLowerCase();
        AppUser user = appUserRepository.findByEmail(emailNorm)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + emailNorm));
        return toDto(user);
    }

    /**
     * Kullanici profil bilgilerini gunceller (kisitli guncelleme).
     *
     * <p>Sadece ad soyad ve opsiyonel olarak sifre guncellenir.
     * Rol, aktiflik, gecerlilik tarihleri gibi yonetimsel alanlar
     * degistirilemez.</p>
     *
     * @param id               kullanici ID'si
     * @param adSoyad          yeni ad soyad bilgisi
     * @param password         yeni sifre (bos veya null ise mevcut sifre korunur)
     * @param telegramUsername telegram kullanici adi (bos veya null ise mevcut korunur)
     * @param phoneNumber      telefon numarasi (bos veya null ise mevcut korunur)
     * @return guncellenen kullanici DTO'su (sifre bilgisi icerilmez)
     * @throws RuntimeException kullanici bulunamazsa
     */
    @Transactional
    public UserDto updateProfil(Long id, String adSoyad, String password,
                                 String telegramUsername, String phoneNumber) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + id));

        if (adSoyad != null && !adSoyad.isBlank()) {
            user.setAdSoyad(adSoyad);
        }

        // Telegram username degistiyse duplicate kontrol ve guncelleme
        if (telegramUsername != null) {
            if (!telegramUsername.isBlank()
                    && !telegramUsername.equalsIgnoreCase(user.getTelegramUsername())
                    && appUserRepository.existsByTelegramUsernameIgnoreCase(telegramUsername)) {
                throw new RuntimeException("Bu Telegram adı zaten kullanılıyor: " + telegramUsername);
            }
            user.setTelegramUsername(normalizeBlankToNull(telegramUsername));
        }

        if (phoneNumber != null) {
            String normalizedPhone = normalizeBlankToNull(phoneNumber);
            if (normalizedPhone != null) {
                normalizedPhone = canonicalizeTurkishPhone(normalizedPhone);
                if (normalizedPhone.isEmpty()) normalizedPhone = null;
            }
            user.setPhoneNumber(normalizedPhone);
        }

        // Sifre sadece dolu gonderilirse guncellenir
        if (password != null && !password.isBlank()) {
            user.setPassword(passwordEncoder.encode(password));
        }

        AppUser saved = appUserRepository.save(user);
        log.info("Kullanici profili guncellendi: {} (ID: {})", saved.getEmail(), saved.getId());
        return toDto(saved);
    }

    /**
     * Kullanicinin giris gecmisini sayfalanmis olarak getirir (backoffice icin).
     *
     * @param userId kullanici ID'si
     * @param limit  maksimum kayit sayisi
     * @return giris gecmisi DTO listesi
     */
    public List<LoginHistoryDto> getLoginHistory(Long userId, int limit) {
        return loginHistoryRepository.findByAppUserIdOrderByLoginDateDesc(userId, PageRequest.of(0, limit))
                .stream()
                .map(h -> LoginHistoryDto.builder()
                        .id(h.getId())
                        .loginDate(h.getLoginDate())
                        .ipAddress(h.getIpAddress())
                        .userAgent(h.getUserAgent())
                        .success(h.isSuccess())
                        .failureReason(h.getFailureReason())
                        .build())
                .toList();
    }

    /**
     * Giris denemesini login_history tablosuna kaydeder.
     *
     * <p>Kayit hatasi giris islemini engellemez (try-catch koruması).</p>
     *
     * @param user    giris yapan kullanici
     * @param request giris istegi (IP ve user-agent bilgisi)
     * @param success basarili mi
     * @param reason  basarisizlik nedeni (basarili ise null)
     */
    private void recordLoginHistory(AppUser user, LoginRequestDto request, boolean success, String reason) {
        try {
            String ip = request.getIpAddress();
            String truncatedIp = ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip;
            LoginHistory history = LoginHistory.builder()
                    .appUser(user)
                    .loginDate(LocalDateTime.now())
                    .ipAddress(truncatedIp)
                    .userAgent(request.getUserAgent() != null && request.getUserAgent().length() > 500
                            ? request.getUserAgent().substring(0, 500) : request.getUserAgent())
                    .success(success)
                    .failureReason(reason)
                    .build();
            loginHistoryRepository.save(history);
        } catch (Exception e) {
            String userEmail = user != null ? user.getEmail() : "unknown";
            log.error("Giris gecmisi kaydi basarisiz (kullanici: {}): {}", userEmail, e.getMessage());
        }
    }

    /**
     * Basarili giris sonrasi kullanici ozet bilgilerini gunceller.
     *
     * <p>Guncelleme hatasi giris islemini engellemez (try-catch koruması).</p>
     *
     * @param user      giris yapan kullanici
     * @param ipAddress istemci IP adresi
     */
    private void updateLoginSummary(AppUser user, String ipAddress) {
        try {
            String truncatedIp = ipAddress != null && ipAddress.length() > 45
                    ? ipAddress.substring(0, 45) : ipAddress;
            appUserRepository.updateLoginSummary(user.getId(), LocalDateTime.now(), truncatedIp);
        } catch (Exception e) {
            log.error("Kullanici giris ozeti guncelleme hatasi ({}): ", user.getEmail(), e);
        }
    }

    /**
     * AppUser entity'sini UserDto'ya donusturur. Sifre bilgisi icerilmez.
     *
     * @param user entity
     * @return DTO
     */
    private UserDto toDto(AppUser user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .adSoyad(user.getAdSoyad())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .userGroup(user.getUserGroup() != null ? user.getUserGroup().name() : "STANDART")
                .telegramUsername(user.getTelegramUsername())
                .phoneNumber(user.getPhoneNumber())
                .validFrom(user.getValidFrom())
                .validTo(user.getValidTo())
                .aktif(user.getAktif())
                .createTime(user.getCreateTime())
                .lastLoginDate(user.getLastLoginDate())
                .lastLoginIp(user.getLastLoginIp())
                .loginCount(user.getLoginCount())
                .build();
    }

    /**
     * Rol string'ini guvenli bir sekilde {@link UserRoleEnum}'a donusturur.
     *
     * @param role rol string'i
     * @return UserRoleEnum degeri
     * @throws RuntimeException rol null, bos veya gecersizse
     */
    private UserRoleEnum parseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new RuntimeException("Rol bilgisi zorunludur");
        }
        try {
            return UserRoleEnum.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Gecersiz rol: " + role + " (ADMIN veya USER olmali)");
        }
    }

    /**
     * Kullanici grubu string'ini guvenli bir sekilde {@link UserGroupEnum}'a donusturur.
     *
     * <p>{@code null} veya bos deger gonderilirse varsayilan olarak
     * {@link UserGroupEnum#STANDART} doner.</p>
     *
     * @param group kullanici grubu string'i
     * @return UserGroupEnum degeri (varsayilan: STANDART)
     */
    private UserGroupEnum parseUserGroup(String group) {
        if (group == null || group.isBlank()) {
            return UserGroupEnum.STANDART;
        }
        try {
            return UserGroupEnum.valueOf(group.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Gecersiz kullanici grubu: '{}', STANDART kullaniliyor", group);
            return UserGroupEnum.STANDART;
        }
    }

    /**
     * Bos veya blank string'i null'a normalize eder.
     *
     * @param value normalize edilecek deger
     * @return trimlenmiş deger veya null (bos/blank ise)
     */
    private String normalizeBlankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    /**
     * Turk telefon numarasini kanonik 10 haneli formata donusturur.
     *
     * <p>Rakam disi karakterleri temizler, 90 ulke kodu ve basa eklenen 0'i cikarir.
     * Ornegin: "+90 532 123 45 67" → "5321234567", "05321234567" → "5321234567"</p>
     *
     * @param raw ham telefon numarasi
     * @return kanonik 10 haneli rakam dizisi veya bos string (null ise)
     */
    private String canonicalizeTurkishPhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        // Sondan 10 hane al (5XXXXXXXXX formatı)
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        return digits;
    }


    /**
     * Email ve telefon numarasi ile kullanici kimligini dogrular.
     *
     * <p>Sifre sifirlama oncesi kimlik dogrulama adimi olarak kullanilir.
     * Telefon numarasindaki rakam disi karakterler (bosluk, tire vb.) temizlenerek karsilastirilir.</p>
     *
     * @param email       kullanicinin e-posta adresi
     * @param phoneNumber kullanicinin telefon numarasi
     * @return kimlik dogrulanirsa {@code true}, aksi halde {@code false}
     */
    public boolean verifyIdentity(String email, String phoneNumber) {
        if (email == null || phoneNumber == null) return false;
        String emailNorm = email.trim().toLowerCase();
        String phoneNorm = canonicalizeTurkishPhone(phoneNumber);
        if (phoneNorm.isEmpty()) return false;
        Optional<AppUser> userOpt = appUserRepository.findByEmail(emailNorm);
        if (userOpt.isEmpty()) return false;
        AppUser user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getAktif())) return false;
        String dbPhone = canonicalizeTurkishPhone(user.getPhoneNumber());
        return dbPhone.equals(phoneNorm);
    }

    /**
     * Email ve telefon dogrulamasindan sonra kullanici sifresini sifirlar.
     *
     * <p>Once {@link #verifyIdentity(String, String)} ile kimlik dogrulanir,
     * ardindan yeni sifre BCrypt ile hashlenerek kaydedilir.</p>
     *
     * @param email       kullanicinin e-posta adresi
     * @param phoneNumber kullanicinin telefon numarasi
     * @param newPassword yeni sifre (minimum 6 karakter)
     * @return sifre basariyla sifirlanirsa {@code true}, aksi halde {@code false}
     */
    @Transactional
    public boolean resetPassword(String email, String phoneNumber, String newPassword) {
        if (!verifyIdentity(email, phoneNumber)) return false;
        if (newPassword == null || newPassword.length() < 6) return false;
        String emailNorm = email.trim().toLowerCase();
        AppUser user = appUserRepository.findByEmail(emailNorm).orElse(null);
        if (user == null) return false;
        user.setPassword(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);
        log.info("[USER] Sifre sifirlandi: email={}", emailNorm);
        return true;
    }

    /**
     * Basarisiz giris yaniti olusturur.
     *
     * @param message hata mesaji
     * @return basarisiz giris yaniti
     */
    private LoginResponseDto fail(String message) {
        return LoginResponseDto.builder()
                .success(false)
                .message(message)
                .build();
    }
}
