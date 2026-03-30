package com.scyborsa.api.controller;

import com.scyborsa.api.dto.auth.LoginRequestDto;
import com.scyborsa.api.dto.auth.LoginResponseDto;
import com.scyborsa.api.dto.auth.ResetPasswordRequestDto;
import com.scyborsa.api.dto.auth.VerifyIdentityRequestDto;
import com.scyborsa.api.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Kimlik dogrulama REST controller'i.
 *
 * <p>Kullanici giris islemini yonetir. {@code /api/v1/auth} altindaki
 * endpoint'leri sunar.</p>
 *
 * @see com.scyborsa.api.service.UserService#authenticate(LoginRequestDto)
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * Kullanici giris islemi.
     *
     * <p>E-posta ve sifre ile giris yapar. Basarili ise 200 OK,
     * basarisiz ise 401 Unauthorized doner.</p>
     *
     * @param request giris istegi (email + password)
     * @return giris yaniti
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@RequestBody LoginRequestDto request,
                                                   HttpServletRequest httpRequest) {
        // UI güvenilir kaynak (localhost) — body'deki IP varsa kullan, yoksa sunucu resolve
        if (request.getIpAddress() == null || request.getIpAddress().isBlank()) {
            request.setIpAddress(resolveClientIp(httpRequest));
        }
        if (request.getUserAgent() == null || request.getUserAgent().isBlank()) {
            request.setUserAgent(httpRequest.getHeader("User-Agent"));
        }

        var response = userService.authenticate(request);
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Kullanici kimlik dogrulama islemi (sifre sifirlama oncesi).
     *
     * <p>Email ve telefon numarasi ile kullanicinin kimligini dogrular.
     * Basarili ise sifre sifirlama adimina gecis yapilabilir.</p>
     *
     * @param request kimlik dogrulama istegi (email + phoneNumber)
     * @return dogrulama sonucu ({@code success: true/false, message: "..."})
     */
    @PostMapping("/verify-identity")
    public ResponseEntity<Map<String, Object>> verifyIdentity(@RequestBody VerifyIdentityRequestDto request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "E-posta adresi zorunludur"
            ));
        }
        if (request.getPhoneNumber() == null || request.getPhoneNumber().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Telefon numarasi zorunludur"
            ));
        }

        boolean verified = userService.verifyIdentity(request.getEmail(), request.getPhoneNumber());
        if (verified) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Kimlik dogrulandi"
            ));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "success", false,
                "message", "Kimlik dogrulanamadi"
        ));
    }

    /**
     * Sifre sifirlama islemi.
     *
     * <p>Email ve telefon numarasi ile kimlik dogrulandiktan sonra
     * yeni sifre belirlenir. Yeni sifre minimum 6 karakter olmalidir.</p>
     *
     * @param request sifre sifirlama istegi (email + phoneNumber + newPassword)
     * @return sifirlama sonucu ({@code success: true/false, message: "..."})
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody ResetPasswordRequestDto request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "E-posta adresi zorunludur"
            ));
        }
        if (request.getPhoneNumber() == null || request.getPhoneNumber().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Telefon numarasi zorunludur"
            ));
        }
        if (request.getNewPassword() == null || request.getNewPassword().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Sifre en az 6 karakter olmalidir"
            ));
        }

        boolean success = userService.resetPassword(
                request.getEmail(), request.getPhoneNumber(), request.getNewPassword());
        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Sifre basariyla sifirlandi"
            ));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "success", false,
                "message", "Kimlik dogrulanamadi veya sifre sifirlanamadi"
        ));
    }

    /**
     * Istemci IP adresini proxy header'larindan veya dogrudan request'ten cikarir.
     *
     * <p>Oncelik sirasi: X-Forwarded-For &gt; X-Real-IP &gt; remoteAddr</p>
     *
     * @param request HTTP istegi
     * @return istemci IP adresi
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        boolean fromTrustedProxy = "127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr);
        if (fromTrustedProxy) {
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank()) return xRealIp.trim();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        return remoteAddr;
    }
}
