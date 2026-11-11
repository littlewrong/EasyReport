package cn.easyreport.framework.config;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transparent, theme-neutral captcha artwork for the ERSync login page.
 * The challenge and its answer are still produced by the configured captcha producer.
 */
public final class ErsyncCaptchaRenderer
{
    private static final int WIDTH = 240;
    private static final int HEIGHT = 88;

    private ErsyncCaptchaRenderer()
    {
    }

    public static BufferedImage createImage(String text)
    {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            ThreadLocalRandom random = ThreadLocalRandom.current();

            String displayText = text.replace('*', '\u00d7').replace('/', '\u00f7').replace('-', '\u2212');
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 54);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            int spacing = 5;
            int textWidth = metrics.stringWidth(displayText) + spacing * Math.max(0, displayText.length() - 1);
            int x = (WIDTH - textWidth) / 2;
            int baseline = (HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.setColor(new Color(45, 55, 72));

            for (int i = 0; i < displayText.length(); i++)
            {
                String character = String.valueOf(displayText.charAt(i));
                int characterWidth = metrics.stringWidth(character);
                AffineTransform transform = graphics.getTransform();
                graphics.rotate(random.nextDouble(-0.045, 0.045), x + characterWidth / 2.0, HEIGHT / 2.0);
                graphics.drawString(character, x, baseline + random.nextInt(-1, 2));
                graphics.setTransform(transform);
                x += characterWidth + spacing;
            }

            graphics.setColor(new Color(45, 55, 72, 32));
            graphics.setStroke(new BasicStroke(1.1f));
            for (int i = 0; i < 2; i++)
            {
                graphics.draw(new QuadCurve2D.Double(10, random.nextInt(24, 65),
                        WIDTH / 2.0, random.nextInt(12, 77), WIDTH - 10, random.nextInt(24, 65)));
            }
        }
        finally
        {
            graphics.dispose();
        }
        return image;
    }
}
