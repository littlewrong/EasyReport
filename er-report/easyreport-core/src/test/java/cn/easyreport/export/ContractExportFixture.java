package cn.easyreport.export;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.context.support.GenericApplicationContext;

import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.build.HideRowColumnBuilder;
import cn.easyreport.definition.Alignment;
import cn.easyreport.definition.CellStyle;
import cn.easyreport.definition.Expand;
import cn.easyreport.definition.Orientation;
import cn.easyreport.definition.PagingMode;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.PaperType;
import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.export.pdf.font.FontBuilder;
import cn.easyreport.export.pdf.font.FontRegister;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;

/** Shared fixtures exercise real embedded Chinese fonts and the original report rows. */
public final class ContractExportFixture {
    private static GenericApplicationContext fontContext;

    private ContractExportFixture() {
    }

    public static synchronized void initializeFonts() {
        if (fontContext != null) {
            return;
        }
        File root = fontRoot();
        try {
            GenericApplicationContext context = new GenericApplicationContext();
            context.setClassLoader(new URLClassLoader(new URL[]{root.toURI().toURL()},
                    ContractExportFixture.class.getClassLoader()));
            registerFont(context, "song", "宋体", "cn/easyreport/font/songti/SIMSUN.TTC");
            registerFont(context, "hei", "黑体", "cn/easyreport/font/heiti/SIMHEI.TTF");
            context.refresh();
            new FontBuilder().setApplicationContext(context);
            fontContext = context;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize repository fonts for contract export tests", ex);
        }
    }

    private static void registerFont(GenericApplicationContext context, String beanName,
            final String family, final String path) {
        context.getBeanFactory().registerSingleton(beanName, new FontRegister() {
            @Override
            public String getFontName() {
                return family;
            }

            @Override
            public String getFontPath() {
                return path;
            }
        });
    }

    private static File fontRoot() {
        File[] roots = {
                new File("../easyreport-font/src/main/java"),
                new File("easyreport-font/src/main/java"),
                new File(System.getProperty("basedir", "."), "../easyreport-font/src/main/java")
        };
        for (File root : roots) {
            if (new File(root, "cn/easyreport/font/songti/SIMSUN.TTC").isFile()) {
                return root;
            }
        }
        throw new IllegalStateException("Repository font resources are not available from the test working directory");
    }

    public static Report report(String html) {
        initializeFonts();
        Report report = new Report();
        report.setReportFullName("contract-export-fixture");
        Paper paper = new Paper();
        paper.setPaperType(PaperType.A4);
        paper.setOrientation(Orientation.portrait);
        paper.setPagingMode(PagingMode.fitpage);
        paper.setWidth(595);
        paper.setHeight(842);
        paper.setLeftMargin(72);
        paper.setRightMargin(72);
        paper.setTopMargin(72);
        paper.setBottomMargin(72);
        report.setPaper(paper);

        List<Row> rows = new ArrayList<Row>();
        Row row = new Row(rows);
        row.setHeight(24);
        row.setTemplateRowNumber(1);
        row.setRowKey("r1");
        rows.add(row);
        report.setRows(rows);
        List<Column> columns = new ArrayList<Column>();
        Column column = new Column(columns);
        column.setWidth(451);
        column.setTemplateColumnNumber(1);
        columns.add(column);
        report.setColumns(columns);

        CellStyle style = new CellStyle();
        style.setFontFamily("宋体");
        style.setFontSize(12);
        style.setLineHeight(1.5f);
        style.setAlign(Alignment.left);
        style.setValign(Alignment.top);
        Cell cell = new Cell();
        cell.setName("A1");
        cell.setRow(row);
        cell.setColumn(column);
        cell.setCellStyle(style);
        cell.setExpand(Expand.None);
        cell.setValue(new SimpleValue(html));
        cell.setHtml(true);
        cell.setData(html);
        row.getCells().add(cell);
        column.getCells().add(cell);
        report.setRootCell(cell);
        report.addCell(cell);
        new Context(null, report, Collections.<String, Dataset>emptyMap(), null,
                Collections.<String, Object>emptyMap(), new HideRowColumnBuilder());
        return report;
    }

    public static String longContract() {
        StringBuilder html = new StringBuilder("<h1 style='font-family:黑体;font-size:18pt;text-align:center'>合同正文导出测试</h1>");
        html.append("<p>本文用于验证中文合同正文的格式与分页，不包含真实合同信息。</p>");
        for (int index = 1; index <= 60; index++) {
            String marker = String.format(java.util.Locale.ROOT, "CLAUSE_%03d", index);
            html.append("<p style='text-indent:24pt;line-height:1.5;margin-bottom:6pt'>")
                    .append("第").append(index).append("条 履行安排 ")
                    .append("双方应根据约定的时间与方式履行各自义务，并对交付内容进行书面核对。")
                    .append("有关变更应保留清晰记录，涉及的资料按照约定妥善保存，便于后续查阅和核实。")
                    .append("本条包含连续中文、<strong>重点事项</strong>及<u>签署内容</u>，用于检查自动换行、样式继承与跨页连续性。 ")
                    .append(marker).append("</p>");
        }
        return html.append("<p style='text-align:right'>签署位置 END_OF_CONTRACT_060</p>").toString();
    }

    public static String lineHeightSample() {
        return "<h1 style='font-family:黑体;font-size:18pt;text-align:center;margin-bottom:10pt'>合同正文行高验证</h1>"
                +"<p style='font-size:12pt;line-height:1.5;margin-bottom:8pt'>"
                +"MULTIPLE_A 这是 1.5 倍行距的第一行<br>MULTIPLE_B 这是 1.5 倍行距的第二行</p>"
                +"<p style='font-size:12pt;line-height:24px;margin-bottom:8pt'>"
                +"FIXED_A 固定 18pt 行高，<span style='font-size:16pt'>混合字号</span>"
                +"<br>FIXED_B 这一行字号恢复 12pt，行距保持固定</p>"
                +"<div style='font-size:12pt;line-height:24pt'><p style='font-size:16pt;margin-bottom:8pt'>"
                +"INHERIT_A 继承父级固定 24pt 行高<br>INHERIT_B 字号变大，固定行高保持不变</p></div>"
                +"<div style='line-height:2'><p style='font-size:12pt;line-height:normal;margin-bottom:8pt'>"
                +"NORMAL_A 覆盖父级两倍行距，恢复默认<br>NORMAL_B 默认采用 1.2 倍行距</p></div>"
                +"<p style='font-size:12pt;margin-bottom:8pt'><span style='line-height:2'>"
                +"SPAN_A 整段由 span 设置两倍行距，<strong>保留加粗</strong>"
                +"<br>SPAN_B 这一行仍然采用两倍行距</span></p>"
                +"<p style='font-size:10pt;line-height:0.8;margin-bottom:8pt'>"
                +"COMPACT_A abc def ghi jkl mno<br>COMPACT_B pqr stu vwx yz</p>"
                +"<p>行高验证结束 LINE_HEIGHT_SAMPLE_END</p>";
    }
}
