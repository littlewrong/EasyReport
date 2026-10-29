package cn.easyreport.export.html;

import java.awt.Font;
import java.awt.Insets;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import javax.swing.JEditorPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import javax.swing.text.html.ParagraphView;
import javax.swing.text.html.StyleSheet;

import cn.easyreport.build.Context;
import cn.easyreport.definition.CellStyle;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;

/**
 * Estimates the height of the supported HTML fragment before server-side paging.
 * Swing is deliberately used only for an undisplayed, per-call text document:
 * there is no shared editor, event-dispatch-thread wait or URL document loading.
 * Its HTML/CSS metrics approximate, rather than reproduce, browser layout.
 */
public final class HtmlCellLayout {
    private static final double PIXELS_PER_POINT = 96d / 72d;

    private HtmlCellLayout() {
    }

    public static void computeHeight(Cell cell, Context context) {
        if (cell == null || context == null || cell.getRow() == null || cell.getColumn() == null) {
            return;
        }
        Row row = cell.getRow();
        if (row.isHide() || row.getRealHeight() <= 0 || cell.getColumn().isHide()) {
            return; // Layout must never make a conditionally hidden row visible.
        }
        Object data = cell.getFormatData();
        if (data == null || data.toString().trim().length() == 0) {
            return;
        }
        int width = visibleWidth(cell, context);
        if (width <= 0) {
            return;
        }
        String fragment = HtmlCellContent.sanitize(data.toString(), false);
        if (fragment.trim().length() == 0) {
            return;
        }
        double height = preferredHeight(cell, fragment, Math.max(1d, width * PIXELS_PER_POINT - 2d));
        int required = (int) Math.ceil((height + 2d) / PIXELS_PER_POINT);
        long available = visibleHeight(cell, context);
        if (required > available) {
            long grown = row.getRealHeight() + (long) required - available;
            row.setRealHeight((int) Math.min(Integer.MAX_VALUE, grown));
            refreshBandHeights(context.getReport(), row);
        }
    }

    private static void refreshBandHeights(Report report, Row changedRow) {
        // These totals are initialized from template heights, before HTML layout.
        // Re-sum only affected bands so fit-to-page reserves their actual height.
        if (report.getHeaderRepeatRows().contains(changedRow)) {
            report.setRepeatHeaderRowHeight(totalHeight(report.getHeaderRepeatRows()));
        }
        if (report.getFooterRepeatRows().contains(changedRow)) {
            report.setRepeatFooterRowHeight(totalHeight(report.getFooterRepeatRows()));
        }
        if (report.getTitleRows().contains(changedRow)) {
            report.setTitleRowsHeight(totalHeight(report.getTitleRows()));
        }
        if (report.getSummaryRows().contains(changedRow)) {
            report.setSummaryRowsHeight(totalHeight(report.getSummaryRows()));
        }
    }

    private static int totalHeight(List<Row> rows) {
        long height = 0;
        for (Row row : rows) {
            height += Math.max(0, row.getRealHeight());
        }
        return (int) Math.min(Integer.MAX_VALUE, height);
    }

    private static int visibleWidth(Cell cell, Context context) {
        long width = 0;
        int span = Math.max(1, cell.getColSpan());
        int index = cell.getColumn().getColumnNumber();
        for (int counted = 0; counted < span; index++) {
            Column column = context.getColumn(index);
            if (column == null) {
                break;
            }
            // HideRowColumnBuilder has already removed these columns from spans.
            if (column.isHide()) {
                continue;
            }
            counted++;
            width += Math.max(0, column.getWidth());
        }
        return (int) Math.min(Integer.MAX_VALUE, width);
    }

    private static long visibleHeight(Cell cell, Context context) {
        long height = 0;
        int span = Math.max(1, cell.getRowSpan());
        int index = cell.getRow().getRowNumber();
        for (int counted = 0; counted < span; index++) {
            Row row = context.getRow(index);
            if (row == null) {
                break;
            }
            if (row.isHide()) {
                continue;
            }
            counted++;
            height += Math.max(0, row.getRealHeight());
        }
        return height;
    }

    private static double preferredHeight(Cell cell, String fragment, double width) {
        CellStyle[] styles = {cell.getCellStyle(), cell.getCustomCellStyle(),
                cell.getRow().getCustomCellStyle(), cell.getColumn().getCustomCellStyle()};
        String family = "Dialog";
        int size = 12;
        boolean bold = false;
        boolean italic = false;
        float lineHeight = 0;
        for (CellStyle style : styles) {
            if (style == null) {
                continue;
            }
            if (style.getFontFamily() != null && style.getFontFamily().trim().length() > 0) {
                family = style.getFontFamily().trim();
            }
            if (style.getFontSize() > 0) {
                size = style.getFontSize();
            }
            if (style.getBold() != null) {
                bold = style.getBold();
            }
            if (style.getItalic() != null) {
                italic = style.getItalic();
            }
            if (style.getLineHeight() > 0) {
                lineHeight = style.getLineHeight();
            }
        }
        Font font = new Font(family, (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0),
                Math.max(1, (int) Math.round(size * PIXELS_PER_POINT)));
        // A font family is data; do not let it introduce rules into the stylesheet.
        family = font.getFamily().replaceAll("[^\\p{L}\\p{N} _-]", "");
        HTMLEditorKit kit = new LayoutEditorKit();
        StyleSheet sheet = new StyleSheet();
        sheet.addStyleSheet(kit.getStyleSheet());
        sheet.addRule("html, body { margin: 0; padding: 0; }");
        sheet.addRule("body { font-family: '" + family + "'; font-size: " + size + "pt;"
                + "font-weight: " + (bold ? "bold" : "normal") + "; font-style: "
                + (italic ? "italic" : "normal") + ";"
                + (lineHeight > 0 ? "line-height:" + lineHeight + ";" : "") + " }");
        sheet.addRule(HtmlCellContent.getStyle());
        kit.setStyleSheet(sheet);

        JEditorPane pane = new JEditorPane();
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.putClientProperty(JEditorPane.W3C_LENGTH_UNITS, Boolean.TRUE);
        pane.setEditorKit(kit);
        pane.setEditable(false);
        pane.setBorder(null);
        pane.setMargin(new Insets(0, 0, 0, 0));
        pane.setFont(font);
        HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
        document.setAsynchronousLoadPriority(-1);
        document.putProperty("IgnoreCharsetDirective", Boolean.TRUE);
        pane.setDocument(document);
        try {
            kit.read(new StringReader("<html><body><div class='easyreport-rich-text'>" + fragment
                    + "</div></body></html>"), document, 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to measure sanitized HTML cell", ex);
        } catch (BadLocationException ex) {
            throw new IllegalStateException("Unable to measure sanitized HTML cell", ex);
        }
        View view = pane.getUI().getRootView(pane);
        view.setSize((float) width, Float.MAX_VALUE);
        return view.getPreferredSpan(View.Y_AXIS);
    }

    private static final class LayoutEditorKit extends HTMLEditorKit {
        private static final long serialVersionUID = 1L;
        private final ViewFactory factory = new HTMLFactory() {
            @Override
            public View create(Element element) {
                View view = super.create(element);
                if (view instanceof ParagraphView) {
                    return new LayoutParagraph(element);
                }
                if (view.getClass() == InlineView.class) {
                    return new InlineView(element) {
                        @Override
                        public float getMinimumSpan(int axis) {
                            // Match the rich-text wrapper's overflow-wrap rule.
                            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                        }
                    };
                }
                return view;
            }
        };

        @Override
        public ViewFactory getViewFactory() {
            return factory;
        }
    }

    private static final class LayoutParagraph extends ParagraphView {
        LayoutParagraph(Element element) {
            super(element);
        }

        @Override
        protected void setPropertiesFromAttributes() {
            super.setPropertiesFromAttributes();
            Object value = getAttributes().getAttribute(CSS.Attribute.LINE_HEIGHT);
            if (value == null) {
                return;
            }
            String text = value.toString().trim();
            try {
                float ratio;
                if (text.endsWith("%")) {
                    ratio = Float.parseFloat(text.substring(0, text.length() - 1)) / 100f;
                } else if (text.endsWith("px") || text.endsWith("pt")) {
                    float pixels = Float.parseFloat(text.substring(0, text.length() - 2));
                    if (text.endsWith("pt")) {
                        pixels *= PIXELS_PER_POINT;
                    }
                    ratio = pixels / getStyleSheet().getFont(getAttributes()).getSize2D();
                } else {
                    ratio = Float.parseFloat(text);
                }
                if (Float.isFinite(ratio) && ratio > 0) {
                    setLineSpacing(Math.max(0, ratio - 1f));
                }
            } catch (NumberFormatException ignored) {
                // "normal" and unsupported CSS units retain normal font metrics.
            }
        }
    }
}
