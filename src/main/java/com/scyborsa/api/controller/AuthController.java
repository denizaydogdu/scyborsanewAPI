package com.scyborsa.api.controller;

import com.scyborsa.api.dto.auth.LoginRequestDto;
import com.scyborsa.api.dto.auth.LoginResponseDto;
import com.scyborsa.api.service.UserService;
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
    public ResponseEntity<LoginResponseDto> login(@RequestBody LoginRequestDto request) {
        var response = userService.authenticate(request);
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
}
