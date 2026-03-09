package com.scyborsa.api.service;

import com.scyborsa.api.dto.UserDto;
import com.scyborsa.api.dto.auth.LoginRequestDto;
import com.scyborsa.api.dto.auth.LoginResponseDto;
import com.scyborsa.api.enums.UserRoleEnum;
import com.scyborsa.api.model.AppUser;
import com.scyborsa.api.repository.AppUserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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

    private final AppUserRepository appUserRepository;

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
            return fail("Hatali e-posta veya sifre");
        }

        AppUser user = userOpt.get();

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Giris denemesi basarisiz — yanlis sifre: {}", request.getEmail());
            return fail("Hatali e-posta veya sifre");
        }

        if (!Boolean.TRUE.equals(user.getAktif())) {
            log.warn("Giris denemesi basarisiz — kullanici pasif: {}", request.getEmail());
            return fail("DISABLED");
        }

        LocalDate today = LocalDate.now();

        if (user.getValidTo() != null && user.getValidTo().isBefore(today)) {
            log.warn("Giris denemesi basarisiz — erisim suresi dolmus: {}", request.getEmail());
            return fail("EXPIRED");
        }

        if (user.getValidFrom() != null && user.getValidFrom().isAfter(today)) {
            log.warn("Giris denemesi basarisiz — erisim henuz baslamadi: {}", request.getEmail());
            return fail("NOT_YET_ACTIVE");
        }

        log.info("Basarili giris: {} ({})", user.getEmail(), user.getRole());
        return LoginResponseDto.builder()
                .success(true)
                .role(user.getRole().name())
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

        if (dto.getId() == null) {
            // Yeni kullanici — duplicate username kontrol (username opsiyonel)
            if (dto.getUsername() != null && !dto.getUsername().isBlank()
                    && appUserRepository.existsByUsername(dto.getUsername())) {
                throw new RuntimeException("Bu kullanici adi zaten mevcut: " + dto.getUsername());
            }
            // Yeni kullanici — duplicate email kontrol
            if (emailNorm != null && appUserRepository.existsByEmail(emailNorm)) {
                throw new RuntimeException("Bu e-posta adresi zaten mevcut: " + emailNorm);
            }
            if (dto.getPassword() == null || dto.getPassword().isBlank()) {
                throw new RuntimeException("Yeni kullanici icin sifre zorunludur");
            }
            user = AppUser.builder()
                    .username(dto.getUsername())
                    .email(emailNorm)
                    .password(passwordEncoder.encode(dto.getPassword()))
                    .adSoyad(dto.getAdSoyad())
                    .role(parseRole(dto.getRole()))
                    .validFrom(dto.getValidFrom())
                    .validTo(dto.getValidTo())
                    .aktif(dto.getAktif() != null ? dto.getAktif() : true)
                    .build();
        } else {
            // Mevcut kullanici guncelle
            user = appUserRepository.findById(dto.getId())
                    .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + dto.getId()));

            // Username degistiyse duplicate kontrol (username opsiyonel)
            if (dto.getUsername() != null && !dto.getUsername().isBlank()
                    && !dto.getUsername().equals(user.getUsername())
                    && appUserRepository.existsByUsername(dto.getUsername())) {
                throw new RuntimeException("Bu kullanici adi zaten mevcut: " + dto.getUsername());
            }

            // Email degistiyse duplicate kontrol (stored email de normalize edilerek karsilastirilir)
            String storedEmailNorm = user.getEmail() != null ? user.getEmail().trim().toLowerCase() : null;
            if (emailNorm != null && !emailNorm.equals(storedEmailNorm)
                    && appUserRepository.existsByEmail(emailNorm)) {
                throw new RuntimeException("Bu e-posta adresi zaten mevcut: " + emailNorm);
            }

            user.setUsername(dto.getUsername());
            user.setEmail(emailNorm);
            user.setAdSoyad(dto.getAdSoyad());
            user.setRole(parseRole(dto.getRole()));
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
     * @param id      kullanici ID'si
     * @param adSoyad yeni ad soyad bilgisi
     * @param password yeni sifre (bos veya null ise mevcut sifre korunur)
     * @return guncellenen kullanici DTO'su (sifre bilgisi icerilmez)
     * @throws RuntimeException kullanici bulunamazsa
     */
    @Transactional
    public UserDto updateProfil(Long id, String adSoyad, String password) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + id));

        user.setAdSoyad(adSoyad);

        // Sifre sadece dolu gonderilirse guncellenir
        if (password != null && !password.isBlank()) {
            user.setPassword(passwordEncoder.encode(password));
        }

        AppUser saved = appUserRepository.save(user);
        log.info("Kullanici profili guncellendi: {} (ID: {})", saved.getEmail(), saved.getId());
        return toDto(saved);
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
                .role(user.getRole().name())
                .validFrom(user.getValidFrom())
                .validTo(user.getValidTo())
                .aktif(user.getAktif())
                .createTime(user.getCreateTime())
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
