package cn.easyreport.export.pdf;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;

import cn.easyreport.export.pdf.font.FontBuilder;
import cn.easyreport.export.richtext.RichTextDocument;
import cn.easyreport.export.richtext.RichTextDocument.Run;
import cn.easyreport.export.richtext.RichTextDocument.TextStyle;
import cn.easyreport.model.Cell;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;

/** Writes the supported contract dialect as searchable native PDF text. */
final class PdfRichTextWriter {
    private static volatile BaseFont fallbackFont;

    private PdfRichTextWriter() {
    }

    static void append(PdfPCell target, Cell cell) throws DocumentException, IOException {
        RichTextDocument document = RichTextDocument.from(cell);
        for (RichTextDocument.Paragraph source : document.getParagraphs()) {
            TextStyle style = source.getStyle();
            Paragraph paragraph = new Paragraph();
            paragraph.setAlignment(alignment(style.getAlign()));
            paragraph.setFirstLineIndent(style.getFirstLineIndent());
            paragraph.setIndentationLeft(style.getLeftIndent());
            paragraph.setSpacingBefore(style.getSpaceBefore());
            paragraph.setSpacingAfter(style.getSpaceAfter());
            paragraph.setLeading(0, style.getLineHeight());
            paragraph.setKeepTogether(false);
            for (Run run : source.getRuns()) {
                TextStyle runStyle = run.getStyle();
                Font font = font(runStyle, document.getStyle().getFontFamily(), run.getText());
                Chunk chunk = new Chunk(run.getText(), font);
                if (runStyle.getBackgroundColor() != null) {
                    chunk.setBackground(color(runStyle.getBackgroundColor()));
                }
                if (runStyle.getScript() != 0) {
                    chunk.setTextRise(runStyle.getScript() * runStyle.getFontSize() * .35f);
                    chunk.getFont().setSize(runStyle.getFontSize() * .75f);
                }
                if (runStyle.getLinkUrl() != null) {
                    chunk.setAnchor(runStyle.getLinkUrl());
                }
                paragraph.add(chunk);
            }
            if (paragraph.isEmpty()) {
                paragraph.add(new Chunk("\u00a0", font(style, document.getStyle().getFontFamily(), "\u00a0")));
            }
            target.addElement(paragraph);
        }
    }

    private static int alignment(String align) {
        if ("center".equals(align)) return Element.ALIGN_CENTER;
        if ("right".equals(align)) return Element.ALIGN_RIGHT;
        if ("justify".equals(align)) return Element.ALIGN_JUSTIFIED;
        return Element.ALIGN_LEFT;
    }

    private static Font font(TextStyle style, String defaultFamily, String text) throws DocumentException, IOException {
        Font selected = FontBuilder.getFont(style.getFontFamily(), Math.round(style.getFontSize()),
                style.isBold(), style.isItalic(), style.isUnderline());
        if (selected.getBaseFont() == null && defaultFamily != null) {
            selected = FontBuilder.getFont(defaultFamily, Math.round(style.getFontSize()),
                    style.isBold(), style.isItalic(), style.isUnderline());
        }
        if (selected.getBaseFont() == null || missingGlyph(selected.getBaseFont(), text)) {
            Font inherited = FontBuilder.getFont(defaultFamily, Math.round(style.getFontSize()),
                    style.isBold(), style.isItalic(), style.isUnderline());
            if (inherited.getBaseFont() != null && !missingGlyph(inherited.getBaseFont(), text)) {
                selected = inherited;
            }
        }
        if (selected.getBaseFont() == null || missingGlyph(selected.getBaseFont(), text)) {
            Font chinese = FontBuilder.getFont("宋体", Math.round(style.getFontSize()),
                    style.isBold(), style.isItalic(), style.isUnderline());
            if (chinese.getBaseFont() != null) {
                selected = chinese;
            } else {
                BaseFont fallback = bundledFont();
                if (fallback != null) {
                    selected = new Font(fallback, style.getFontSize(), selected.getStyle());
                }
            }
        }
        selected.setSize(style.getFontSize());
        if (style.isStrike()) {
            selected.setStyle(selected.getStyle() | Font.STRIKETHRU);
        }
        if (style.getColor() != null) {
            selected.setColor(color(style.getColor()));
        }
        return selected;
    }

    private static boolean missingGlyph(BaseFont font, String text) {
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && !font.charExists(codePoint)) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static BaseFont bundledFont() throws DocumentException, IOException {
        BaseFont font = fallbackFont;
        if (font != null) return font;
        synchronized (PdfRichTextWriter.class) {
            if (fallbackFont == null) {
                try (InputStream input = PdfRichTextWriter.class.getClassLoader()
                        .getResourceAsStream("cn/easyreport/font/songti/SIMSUN.TTC")) {
                    if (input != null) {
                        fallbackFont = BaseFont.createFont("SIMSUN.TTC,0", BaseFont.IDENTITY_H,
                                BaseFont.EMBEDDED, true, IOUtils.toByteArray(input), null);
                        fallbackFont.setSubset(true);
                    }
                }
            }
            return fallbackFont;
        }
    }

    private static BaseColor color(String value) {
        int rgb = Integer.parseInt(value.replace("#", ""), 16);
        return new BaseColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
    }
}
