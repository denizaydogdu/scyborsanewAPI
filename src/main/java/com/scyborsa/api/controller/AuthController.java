package com.scyborsa.api.controller;

import com.scyborsa.api.dto.auth.LoginRequestDto;
import com.scyborsa.api.dto.auth.LoginResponseDto;
import com.scyborsa.api.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
