package com.scyborsa.api.enums;

/**
 * Kullanici rol enum'u.
 *
 * <p>Sistem kullanicilarinin rollerini tanimlar.</p>
 * <ul>
 *   <li>{@link #ADMIN} — Yonetim paneli dahil tum sayfalara erisim</li>
 *   <li>{@link #USER} — Sadece normal sayfalara erisim</li>
 * </ul>
 */
public enum UserRoleEnum {
    ADMIN, USER
}
