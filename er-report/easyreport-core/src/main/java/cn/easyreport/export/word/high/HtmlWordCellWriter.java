package cn.easyreport.export.word.high;

import java.math.BigInteger;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.VerticalAlign;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STOnOff;

import cn.easyreport.export.richtext.RichTextDocument;
import cn.easyreport.export.richtext.RichTextDocument.Paragraph;
import cn.easyreport.export.richtext.RichTextDocument.Run;
import cn.easyreport.export.richtext.RichTextDocument.TextStyle;
import cn.easyreport.model.Cell;

/** Writes the supported HTML dialect as editable Word paragraphs and text runs. */
final class HtmlWordCellWriter {
    private HtmlWordCellWriter() {
    }

    static void write(XWPFTableCell target, Cell cell) {
        RichTextDocument content = RichTextDocument.from(cell);
        while (!target.getParagraphs().isEmpty()) {
            target.removeParagraph(0);
        }
        for (Paragraph paragraph : content.getParagraphs()) {
            XWPFParagraph output = target.addParagraph();
            paragraphStyle(output, paragraph.getStyle());
            for (Run run : paragraph.getRuns()) {
                XWPFRun text = createRun(output, run.getStyle().getLinkUrl());
                textStyle(text, run.getStyle());
                String[] lines = run.getText().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                for (int index = 0; index < lines.length; index++) {
                    if (index > 0) {
                        text.addBreak();
                    }
                    if (!lines[index].isEmpty()) {
                        text.setText(lines[index]);
                    }
                }
            }
            if (paragraph.getRuns().isEmpty()) {
                textStyle(output.createRun(), paragraph.getStyle());
            }
        }
        if (target.getParagraphs().isEmpty()) {
            XWPFParagraph empty = target.addParagraph();
            paragraphStyle(empty, content.getStyle());
            textStyle(empty.createRun(), content.getStyle());
        }
    }

    private static void paragraphStyle(XWPFParagraph output, TextStyle style) {
        String align = style.getAlign();
        if ("center".equals(align)) {
            output.setAlignment(ParagraphAlignment.CENTER);
        } else if ("right".equals(align)) {
            output.setAlignment(ParagraphAlignment.RIGHT);
        } else if ("justify".equals(align)) {
            output.setAlignment(ParagraphAlignment.BOTH);
        } else {
            output.setAlignment(ParagraphAlignment.LEFT);
        }
        output.setSpacingBefore(Math.max(0, twips(style.getSpaceBefore())));
        output.setSpacingAfter(Math.max(0, twips(style.getSpaceAfter())));
        output.setIndentationLeft(twips(style.getLeftIndent()));
        if (style.getFirstLineIndent() < 0) {
            output.setIndentationHanging(-twips(style.getFirstLineIndent()));
        } else {
            output.setIndentationFirstLine(twips(style.getFirstLineIndent()));
        }
        output.setSpacingBetween(Math.max(1f, style.getLineHeight()));
        CTPPr properties = output.getCTP().isSetPPr() ? output.getCTP().getPPr() : output.getCTP().addNewPPr();
        properties.addNewKeepNext().setVal(STOnOff.FALSE);
        properties.addNewKeepLines().setVal(STOnOff.FALSE);
        // A long contract paragraph must remain splittable over physical pages.
        properties.addNewWidowControl().setVal(STOnOff.FALSE);
    }

    private static XWPFRun createRun(XWPFParagraph paragraph, String url) {
        if (url == null || url.isEmpty()) {
            return paragraph.createRun();
        }
        String relation = paragraph.getDocument().getPackagePart()
                .addExternalRelationship(url, XWPFRelation.HYPERLINK.getRelation()).getId();
        CTHyperlink link = paragraph.getCTP().addNewHyperlink();
        link.setId(relation);
        return new XWPFHyperlinkRun(link, link.addNewR(), paragraph);
    }

    private static void textStyle(XWPFRun output, TextStyle style) {
        output.setBold(style.isBold());
        output.setItalic(style.isItalic());
        output.setUnderline(style.isUnderline() ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
        output.setStrikeThrough(style.isStrike());
        output.setSubscript(style.getScript() > 0 ? VerticalAlign.SUPERSCRIPT
                : style.getScript() < 0 ? VerticalAlign.SUBSCRIPT : VerticalAlign.BASELINE);
        if (style.getColor() != null) {
            output.setColor(style.getColor());
        }
        CTRPr properties = output.getCTR().isSetRPr() ? output.getCTR().getRPr() : output.getCTR().addNewRPr();
        CTFonts fonts = properties.isSetRFonts() ? properties.getRFonts() : properties.addNewRFonts();
        String family = style.getFontFamily();
        if (family != null && !family.isEmpty()) {
            fonts.setAscii(family);
            fonts.setHAnsi(family);
            fonts.setEastAsia(family);
            fonts.setCs(family);
        }
        BigInteger halfPoints = BigInteger.valueOf(Math.max(2, Math.round(style.getFontSize() * 2f)));
        properties.addNewSz().setVal(halfPoints);
        properties.addNewSzCs().setVal(halfPoints);
        if (style.getBackgroundColor() != null) {
            properties.addNewShd().setFill(style.getBackgroundColor());
        }
    }

    private static int twips(float points) {
        return Math.round(points * 20f);
    }
}
