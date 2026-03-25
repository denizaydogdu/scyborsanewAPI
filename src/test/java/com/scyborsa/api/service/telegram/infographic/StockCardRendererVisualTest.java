package com.scyborsa.api.service.telegram.infographic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;

/**
 * Gorsel test — sample data ile kart uretip PNG dosyaya kaydeder.
 * IDE'den veya {@code mvn exec} ile calistirilabilir.
 */
public class StockCardRendererVisualTest {

    public static void main(String[] args) throws Exception {
        // Font yükleme
        CardTheme.init();

        // Sample data — FORTE ornegi
        StockCardData data = StockCardData.builder()
                .stockName("FORTE")
                .price(112.80)
                .changePercent(3.49)
                .fonPozisyonlari(List.of(
                        StockCardData.FonPozisyonItem.builder()
                                .fonKodu("TTE").lotFormatted("316.4K").agirlik("1.22%").build(),
                        StockCardData.FonPozisyonItem.builder()
                                .fonKodu("ICZ").lotFormatted("168.5K").agirlik("1.78%").build(),
                        StockCardData.FonPozisyonItem.builder()
                                .fonKodu("YHZ").lotFormatted("80.0K").agirlik("0.95%").build()
                ))
                .extraFonCount(12)
                .aliciKurumlar(List.of(
                        StockCardData.KurumItem.builder()
                                .kurumAdi("Yat. Fin.").formattedVolume("+109.38").build(),
                        StockCardData.KurumItem.builder()
                                .kurumAdi("Yapı Kr.").formattedVolume("+109.99").build(),
                        StockCardData.KurumItem.builder()
                                .kurumAdi("Alnus").formattedVolume("+106.36").build()
                ))
                .saticiKurumlar(List.of(
                        StockCardData.KurumItem.builder()
                                .kurumAdi("BofA").formattedVolume("-105.27").build(),
                        StockCardData.KurumItem.builder()
                                .kurumAdi("Halk").formattedVolume("-102.61").build()
                ))
                .takasDagilimi(List.of(
                        StockCardData.TakasItem.builder()
                                .custodianCode("IYF").formattedValue("6.74 Milyon TL").percentage(0.2405).build(),
                        StockCardData.TakasItem.builder()
                                .custodianCode("IYM").formattedValue("3.03 Milyon TL").percentage(0.1082).build(),
                        StockCardData.TakasItem.builder()
                                .custodianCode("ZRY").formattedValue("2.28 Milyon TL").percentage(0.0816).build(),
                        StockCardData.TakasItem.builder()
                                .custodianCode("VKY").formattedValue("2.21 Milyon TL").percentage(0.0790).build(),
                        StockCardData.TakasItem.builder()
                                .custodianCode("GRM").formattedValue("2.06 Milyon TL").percentage(0.0736).build()
                ))
                .alisEmirler(List.of(
                        StockCardData.EmirItem.builder()
                                .time("16:01:58").price("111.00₺").lot("200").from("Midas").to("BofA").build(),
                        StockCardData.EmirItem.builder()
                                .time("16:01:57").price("111.00₺").lot("1").from("Osmanlı").to("BofA").build(),
                        StockCardData.EmirItem.builder()
                                .time("16:01:57").price("111.00₺").lot("4").from("Osmanlı").to("Tacirler").build()
                ))
                .satisEmirler(List.of(
                        StockCardData.EmirItem.builder()
                                .time("16:01:59").price("110.90₺").lot("250").from("Yapı Kr.").to("Halk").build(),
                        StockCardData.EmirItem.builder()
                                .time("16:01:54").price("110.90₺").lot("1.0K").from("Yapı Kr.").to("Marbaş").build(),
                        StockCardData.EmirItem.builder()
                                .time("16:01:53").price("110.80₺").lot("6").from("İş").to("İkon").build()
                ))
                .screenerCount(25)
                .firstSignalTime("16:00")
                .lastSignalTime("16:00")
                .timestamp("16:01:59 - 25 Mart 2026")
                .build();

        // Render
        StockCardRenderer renderer = new StockCardRenderer();
        renderer.init();

        byte[] png = renderer.renderCard(data);

        // PNG dosyaya kaydet
        File outputFile = new File("/tmp/scyborsa-test-card.png");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        ImageIO.write(img, "PNG", outputFile);

        System.out.println("=== TEST SONUCU ===");
        System.out.printf("PNG boyutu: %d bytes (%.1f KB)%n", png.length, png.length / 1024.0);
        System.out.printf("Görüntü boyutu: %dx%d piksel%n", img.getWidth(), img.getHeight());
        System.out.printf("Dosya: %s%n", outputFile.getAbsolutePath());

        // PNG magic bytes kontrolu
        assert png[0] == (byte) 0x89 : "PNG magic byte[0] hatasi";
        assert png[1] == (byte) 0x50 : "PNG magic byte[1] hatasi";
        assert png[2] == (byte) 0x4E : "PNG magic byte[2] hatasi";
        assert png[3] == (byte) 0x47 : "PNG magic byte[3] hatasi";
        System.out.println("PNG magic bytes: OK");

        // Null data testi
        StockCardData emptyData = StockCardData.builder()
                .stockName("EMPTY")
                .price(0.0)
                .changePercent(-1.5)
                .build();

        byte[] emptyPng = renderer.renderCard(emptyData);
        BufferedImage emptyImg = ImageIO.read(new ByteArrayInputStream(emptyPng));
        File emptyFile = new File("/tmp/scyborsa-test-card-empty.png");
        ImageIO.write(emptyImg, "PNG", emptyFile);
        System.out.printf("%nBos veri karti: %dx%d piksel, %d bytes%n",
                emptyImg.getWidth(), emptyImg.getHeight(), emptyPng.length);
        System.out.printf("Dosya: %s%n", emptyFile.getAbsolutePath());

        System.out.println("\n=== TAMAMLANDI ===");
    }
}
