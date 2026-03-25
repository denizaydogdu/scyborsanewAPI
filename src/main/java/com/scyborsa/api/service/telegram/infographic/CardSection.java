package com.scyborsa.api.service.telegram.infographic;

import java.awt.Graphics2D;

/**
 * Telegram infografik kartındaki bir bölümü temsil eden arayüz.
 *
 * <p>Her bölüm kendi yüksekliğini hesaplayabilir ve kendisini çizebilir.
 * Veri yoksa bölüm atlanır (yükseklik 0 döner).</p>
 *
 * @see StockCardRenderer
 */
public interface CardSection {

    /**
     * Bölümü belirtilen y koordinatından itibaren çizer.
     *
     * @param g     çizim bağlamı
     * @param data  hisse kartı verisi
     * @param y     çizime başlanacak y koordinatı
     * @param width kullanılabilir genişlik (piksel)
     * @return bölümün kapladığı toplam yükseklik (piksel)
     */
    int render(Graphics2D g, StockCardData data, int y, int width);

    /**
     * Bölümün kaplayacağı yüksekliği hesaplar (çizim yapmadan).
     *
     * @param data hisse kartı verisi
     * @return tahmini yükseklik (piksel); veri yoksa 0
     */
    int measureHeight(StockCardData data);
}
