package com.scyborsa.api.dto.bilanco;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Finansal rasyo (oran) detay DTO'su.
 *
 * <p>Bir hissenin temel finansal oranlarini icerir. Degerler Gate Velzon API'den
 * alinir ve degerleme, borc, likidite, faaliyet ve karlilik kategorilerinde gruplanir.</p>
 *
 * <h3>Deger Kategorileri:</h3>
 * <ul>
 *   <li><b>Degerleme:</b> fk, pddd, fdfavok, favok, beta, temettuVerimi, hisseBasinaKar, hisseBasinaDefterDegeri</li>
 *   <li><b>Borc:</b> toplamBorcToplamVarlik, faaliyetGeliriFaizGideri, kaldiracOrani, borclarOzsemaye, maddiDuranVarlikOzsemaye, kVadeBorclarToplamBorclar</li>
 *   <li><b>Likidite:</b> cariOran, likitOran, nakitOran</li>
 *   <li><b>Faaliyet:</b> alacakDevirHizi, alacakTahsilSuresi, stokDevirHizi, stoktaKalmaSuresi, netIsletmeSermayeDevirHizi, ozkaynakDevirHizi, aktifDevirHizi, ticariBorcDevirHizi, ticariBorcOdemeSuresi</li>
 *   <li><b>Karlilik:</b> brutKarMarji, netKarMarji, faaliyetKarMarji, aktifKarlilikMarji, ozsermayeKarlilikMarji</li>
 *   <li><b>Kar/Zarar:</b> esasFaaliyetKarZarari, finGidOncFaaliyetKarZarari, netDonemKarZarari, netIsletmeSermayesi</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.bilanco.BilancoService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RasyoDetayDto {

    /** Hisse sembolü (orn. GARAN). */
    private String sembol;

    // ── Degerleme Oranlari ──

    /** Fiyat/Kazanc orani (P/E). */
    private Double fk;

    /** Piyasa Degeri / Defter Degeri (P/B). */
    private Double pddd;

    /** Firma Degeri / FAVOK (EV/EBITDA). */
    private Double fdfavok;

    /** FAVOK (EBITDA) degeri. */
    private Double favok;

    /** Beta katsayisi. */
    private Double beta;

    /** Temettu verimi (%). */
    private Double temettuVerimi;

    /** Hisse basina kar (EPS). */
    private Double hisseBasinaKar;

    /** Hisse basina defter degeri. */
    private Double hisseBasinaDefterDegeri;

    // ── Borc Oranlari ──

    /** Toplam Borc / Toplam Varlik. */
    private Double toplamBorcToplamVarlik;

    /** Faaliyet Geliri / Faiz Gideri. */
    private Double faaliyetGeliriFaizGideri;

    /** Kaldirac orani. */
    private Double kaldiracOrani;

    /** Borclar / Ozsermaye. */
    private Double borclarOzsemaye;

    /** Maddi Duran Varlik / Ozsermaye. */
    private Double maddiDuranVarlikOzsemaye;

    /** Kisa Vadeli Borclar / Toplam Borclar. */
    private Double kVadeBorclarToplamBorclar;

    // ── Likidite Oranlari ──

    /** Cari oran. */
    private Double cariOran;

    /** Likit oran. */
    private Double likitOran;

    /** Nakit oran. */
    private Double nakitOran;

    // ── Faaliyet Oranlari ──

    /** Alacak devir hizi. */
    private Double alacakDevirHizi;

    /** Alacak tahsil suresi (gun). */
    private Double alacakTahsilSuresi;

    /** Stok devir hizi. */
    private Double stokDevirHizi;

    /** Stokta kalma suresi (gun). */
    private Double stoktaKalmaSuresi;

    /** Net isletme sermayesi devir hizi. */
    private Double netIsletmeSermayeDevirHizi;

    /** Ozkaynak devir hizi. */
    private Double ozkaynakDevirHizi;

    /** Aktif devir hizi. */
    private Double aktifDevirHizi;

    /** Ticari borc devir hizi. */
    private Double ticariBorcDevirHizi;

    /** Ticari borc odeme suresi (gun). */
    private Double ticariBorcOdemeSuresi;

    // ── Karlilik Oranlari ──

    /** Brut kar marji (%). */
    private Double brutKarMarji;

    /** Net kar marji (%). */
    private Double netKarMarji;

    /** Faaliyet kar marji (%). */
    private Double faaliyetKarMarji;

    /** Aktif karlilik marji (ROA) (%). */
    private Double aktifKarlilikMarji;

    /** Ozsermaye karlilik marji (ROE) (%). */
    private Double ozsermayeKarlilikMarji;

    // ── Kar/Zarar Kalemleri ──

    /** Esas faaliyet kar/zarari. */
    private Double esasFaaliyetKarZarari;

    /** Finansman giderleri oncesi faaliyet kar/zarari. */
    private Double finGidOncFaaliyetKarZarari;

    /** Net donem kar/zarari. */
    private Double netDonemKarZarari;

    /** Net isletme sermayesi. */
    private Double netIsletmeSermayesi;
}
