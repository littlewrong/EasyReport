package cn.easyreport.export.pdf;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

import cn.easyreport.build.Context;
import cn.easyreport.build.paging.HeaderFooter;
import cn.easyreport.definition.Band;
import cn.easyreport.definition.HeaderFooterDefinition;
import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.export.ContractExportFixture;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.itextpdf.text.pdf.parser.TextRenderInfo;
import com.itextpdf.text.pdf.parser.Vector;

public class PdfContractExportTest {
    @Test
    public void writesNativeParagraphAndRunFormatting() throws Exception {
        Report report=ContractExportFixture.report("<p style='text-align:justify;text-indent:24pt;"
                +"margin-top:6pt;margin-bottom:9pt;line-height:1.5'><strong><u>"
                +"<span style='color:#123456;font-size:16pt;background-color:#ffeecc'>中文正文</span>"
                +"</u></strong><em>斜体</em><s>删除</s><sup>上标</sup></p>");
        PdfPCell cell=new PdfPCell();
        PdfRichTextWriter.append(cell,report.getRootCell());
        Paragraph paragraph=(Paragraph)cell.getCompositeElements().get(0);
        assertEquals(Element.ALIGN_JUSTIFIED,paragraph.getAlignment());
        assertEquals(24f,paragraph.getFirstLineIndent(),.01f);
        assertEquals(6f,paragraph.getSpacingBefore(),.01f);
        assertEquals(9f,paragraph.getSpacingAfter(),.01f);
        assertEquals(1.5f,paragraph.getMultipliedLeading(),.01f);
        List<Chunk> chunks=paragraph.getChunks();
        Chunk chinese=chunks.get(0);
        assertEquals("中文正文",chinese.getContent());
        assertEquals(16f,chinese.getFont().getSize(),.01f);
        assertTrue(chinese.getFont().isBold());
        assertTrue(chinese.getFont().isUnderlined());
        assertNotNull(chinese.getFont().getBaseFont());
        assertEquals(new BaseColor(0x12,0x34,0x56),chinese.getFont().getColor());
        assertEquals(255,chinese.getFont().getColor().getAlpha());
        assertTrue(chinese.getAttributes().containsKey(Chunk.BACKGROUND));
        assertTrue(chunks.get(1).getFont().isItalic());
        assertTrue((chunks.get(2).getFont().getStyle()&Font.STRIKETHRU)!=0);
        assertTrue(chunks.get(3).getTextRise()>0);
    }

    @Test
    public void fixedLineHeightKeepsPointsAcrossInheritedAndInlineFontSizes() throws Exception {
        Report report=ContractExportFixture.report("<div style='font-size:12pt;line-height:24px'>"
                +"<p style='font-size:14pt'>固定行高<span style='font-size:18pt'>混合字号</span><br>第二行</p>"
                +"<p style='font-size:16pt'>子段落继承固定行高</p>"
                +"<p style='line-height:normal'>恢复默认行高</p></div>");
        PdfPCell cell=new PdfPCell();
        PdfRichTextWriter.append(cell,report.getRootCell());
        assertEquals(3,cell.getCompositeElements().size());
        Paragraph mixed=(Paragraph)cell.getCompositeElements().get(0);
        assertEquals(18f,mixed.getLeading(),.01f);
        assertEquals(0f,mixed.getMultipliedLeading(),.01f);
        assertEquals(14f,mixed.getChunks().get(0).getFont().getSize(),.01f);
        assertEquals(18f,mixed.getChunks().get(1).getFont().getSize(),.01f);
        Paragraph inherited=(Paragraph)cell.getCompositeElements().get(1);
        assertEquals(18f,inherited.getLeading(),.01f);
        assertEquals(0f,inherited.getMultipliedLeading(),.01f);
        Paragraph normal=(Paragraph)cell.getCompositeElements().get(2);
        assertEquals(0f,normal.getLeading(),.01f);
        assertEquals(1.2f,normal.getMultipliedLeading(),.01f);
    }

    @Test
    public void fractionalAndWholeParagraphSpanLineHeightsAreWrittenWithoutChangingLocalSpans() throws Exception {
        Report report=ContractExportFixture.report("<p style='line-height:0.8'>紧凑行距<br>第二行</p>"
                +"<p><span style='line-height:2'>整段行高<strong>加粗</strong><br>第二行</span></p>"
                +"<p><span style='line-height:2'>局部文字</span>普通文字</p>");
        PdfPCell cell=new PdfPCell();
        PdfRichTextWriter.append(cell,report.getRootCell());
        assertEquals(3,cell.getCompositeElements().size());
        Paragraph compact=(Paragraph)cell.getCompositeElements().get(0);
        assertEquals(0f,compact.getLeading(),.01f);
        assertEquals(.8f,compact.getMultipliedLeading(),.01f);
        Paragraph uniform=(Paragraph)cell.getCompositeElements().get(1);
        assertEquals(0f,uniform.getLeading(),.01f);
        assertEquals(2f,uniform.getMultipliedLeading(),.01f);
        assertTrue(uniform.getChunks().get(1).getFont().isBold());
        Paragraph local=(Paragraph)cell.getCompositeElements().get(2);
        assertEquals(1.5f,local.getMultipliedLeading(),.01f);
    }

    @Test
    public void longChineseContractFlowsWithoutTruncationOrDuplication() throws Exception {
        Report report=ContractExportFixture.report(ContractExportFixture.longContract());
        report.getRootCell().doDataWrapCompute(report.getContext());
        assertTrue(report.getRows().get(0).getRealHeight()>842);
        PdfReader reader=new PdfReader(export(report));
        try{
            assertTrue(reader.getNumberOfPages()>=3);
            String text=allText(reader);
            assertTrue(text,text.contains("合同正文导出测试"));
            assertTrue(text,text.contains("签署位置"));
            for(int index=1;index<=60;index++){
                assertEquals(String.valueOf(index),1,occurrences(text,String.format(Locale.ROOT,"CLAUSE_%03d",index)));
            }
            assertEquals(1,occurrences(text,"END_OF_CONTRACT_060"));
            assertFalse(text.contains("<p"));
            assertFalse(text.contains("<strong>"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void oversizedEstimatedHtmlHeightDoesNotCreateBlankContinuationPages() throws Exception {
        Report report=ContractExportFixture.report("<p>短合同 SHORT_CONTRACT_END</p>");
        report.getRows().get(0).setRealHeight(8000);
        PdfReader reader=new PdfReader(export(report));
        try{
            assertEquals(1,reader.getNumberOfPages());
            assertTrue(allText(reader).contains("SHORT_CONTRACT_END"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void aSingleLongParagraphCanContinueOnAnotherPage() throws Exception {
        StringBuilder source=new StringBuilder("<p>");
        for(int index=0;index<400;index++) source.append("合同条款中的连续段落应当完整保留。 ");
        source.append("SINGLE_PARAGRAPH_END</p>");
        PdfReader reader=new PdfReader(export(ContractExportFixture.report(source.toString())));
        try{
            assertTrue(reader.getNumberOfPages()>1);
            assertEquals(1,occurrences(allText(reader),"SINGLE_PARAGRAPH_END"));
            assertEquals(400,occurrences(allText(reader).replaceAll("\\s+",""),"合同条款中的连续段落应当完整保留。"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void latinOnlyFontFallsBackWithoutDroppingChineseGlyphs() throws Exception {
        ContractExportFixture.initializeFonts();
        File font=new File("../easyreport-font/src/main/java/cn/easyreport/font/arial/ARIAL.TTF");
        if(!font.isFile()) font=new File("easyreport-font/src/main/java/cn/easyreport/font/arial/ARIAL.TTF");
        assertTrue(font.isFile());
        FontFactory.register(font.getAbsolutePath(),"ContractLatinOnly");
        Report report=ContractExportFixture.report("<p><span style='font-family:ContractLatinOnly'>中文 FONT_FALLBACK</span></p>");
        PdfReader reader=new PdfReader(export(report));
        try{
            assertTrue(allText(reader).contains("中文 FONT_FALLBACK"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void defaultTextColorIsOpaqueBlack() throws Exception {
        PdfPCell cell=new PdfPCell();
        PdfRichTextWriter.append(cell,ContractExportFixture.report("<p>默认黑色</p>").getRootCell());
        Chunk chunk=((Paragraph)cell.getCompositeElements().get(0)).getChunks().get(0);
        assertEquals(new BaseColor(0,0,0),chunk.getFont().getColor());
        assertEquals(255,chunk.getFont().getColor().getAlpha());
    }

    @Test
    public void plainNeighborsDoNotRetainHugeEstimatedHtmlRowHeight() throws Exception {
        Report report=ContractExportFixture.report("<p>短正文 MIXED_ROW_END</p>");
        report.getColumn(1).setWidth(351);
        Column labelColumn=new Column(report.getColumns());
        labelColumn.setWidth(100);
        report.getColumns().add(0,labelColumn);
        Cell label=report.getRootCell().newCell();
        label.setName("LABEL");
        label.setColumn(labelColumn);
        label.setHtml(false);
        label.setValue(new SimpleValue("LABEL_TEXT"));
        label.setData("LABEL_TEXT");
        report.addCell(label);
        report.getRows().get(0).setRealHeight(8000);
        PdfReader reader=new PdfReader(export(report));
        try{
            assertEquals(1,reader.getNumberOfPages());
            assertTrue(allText(reader).contains("MIXED_ROW_END"));
            assertTrue(allText(reader).contains("LABEL_TEXT"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void legacyPaginationCopiesDoNotRepeatVerticallyMergedHtml() throws Exception {
        Report report=ContractExportFixture.report(ContractExportFixture.longContract());
        Row second=new Row(report.getRows());
        second.setHeight(24);
        second.setRowKey("r2");
        second.setTemplateRowNumber(2);
        report.getRows().add(second);
        report.getRootCell().setRowSpan(2);
        report.getRootCell().doDataWrapCompute(report.getContext());
        assertTrue(report.getPages().size()>1);
        PdfReader reader=new PdfReader(export(report));
        try{
            String text=allText(reader);
            assertTrue(reader.getNumberOfPages()>1);
            assertEquals(1,occurrences(text,"CLAUSE_001"));
            assertEquals(1,occurrences(text,"CLAUSE_060"));
            assertEquals(1,occurrences(text,"END_OF_CONTRACT_060"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void physicalPageHeadersUseAccurateTotalsAndRestoreReportContext() throws Exception {
        Report report=ContractExportFixture.report(ContractExportFixture.longContract());
        report.getContext().setPageIndex(7);
        report.getContext().setTotalPages(91);
        report.setFooter(new HeaderFooterDefinition(){
            private static final long serialVersionUID=1L;
            @Override
            public HeaderFooter buildHeaderFooter(int pageIndex,Context context){
                HeaderFooter footer=new HeaderFooter();
                footer.setCenter("PAGE_"+pageIndex+"_OF_"+context.getTotalPages());
                return footer;
            }
        });
        PdfReader reader=new PdfReader(export(report));
        try{
            int total=reader.getNumberOfPages();
            for(int index=1;index<=total;index++){
                assertTrue(PdfTextExtractor.getTextFromPage(reader,index).contains("PAGE_"+index+"_OF_"+total));
            }
            assertEquals(7,report.getContext().getPageIndex());
            assertEquals(91,report.getContext().getTotalPages());
        }finally{
            reader.close();
        }
    }

    @Test
    public void prefixHeaderRowRepeatsOnEveryPhysicalPage() throws Exception {
        Report report=ContractExportFixture.report(ContractExportFixture.longContract());
        Row header=new Row(report.getRows());
        header.setHeight(24);
        header.setBand(Band.headerrepeat);
        header.setRowKey("header");
        report.getRows().add(0,header);
        report.getHeaderRepeatRows().add(header);
        Cell cell=report.getRootCell().newCell();
        cell.setRow(header);
        cell.setName("HEADER");
        cell.setHtml(false);
        cell.setValue(new SimpleValue("CONTRACT_HEADER"));
        cell.setData("CONTRACT_HEADER");
        report.addCell(cell);
        PdfReader reader=new PdfReader(export(report));
        try{
            for(int index=1;index<=reader.getNumberOfPages();index++){
                assertTrue(PdfTextExtractor.getTextFromPage(reader,index).contains("CONTRACT_HEADER"));
            }
        }finally{
            reader.close();
        }
    }

    @Test
    public void disabledHtmlFlagPreservesLiteralTextExport() throws Exception {
        Report report=ContractExportFixture.report("<b>plain text</b>");
        report.getRootCell().setHtml(false);
        PdfReader reader=new PdfReader(export(report));
        try{
            assertEquals(1,reader.getNumberOfPages());
            assertTrue(allText(reader).contains("<b>plain text</b>"));
        }finally{
            reader.close();
        }
    }

    @Test
    public void createsContractFixtureForVisualReview() throws Exception {
        byte[] pdf=export(ContractExportFixture.report(ContractExportFixture.longContract()));
        Files.createDirectories(Paths.get("target/contract-export-qa"));
        Files.write(Paths.get("target/contract-export-qa/contract.pdf"),pdf);
        assertTrue(pdf.length>1000);
    }

    @Test
    public void createsLineHeightFixtureForVisualReview() throws Exception {
        byte[] pdf=export(ContractExportFixture.report(ContractExportFixture.lineHeightSample()));
        Files.createDirectories(Paths.get("target/line-height-qa"));
        Files.write(Paths.get("target/line-height-qa/line-height.pdf"),pdf);
        PdfReader reader=new PdfReader(pdf);
        try{
            assertEquals(1,reader.getNumberOfPages());
            final String[] markers={"MULTIPLE_A","MULTIPLE_B","FIXED_A","FIXED_B",
                    "INHERIT_A","INHERIT_B","NORMAL_A","NORMAL_B","SPAN_A","SPAN_B",
                    "COMPACT_A","COMPACT_B","LINE_HEIGHT_SAMPLE_END"};
            final Map<String,Float> baselines=new HashMap<String,Float>();
            String text=PdfTextExtractor.getTextFromPage(reader,1,new LocationTextExtractionStrategy(){
                @Override
                public void renderText(TextRenderInfo info){
                    super.renderText(info);
                    for(String marker:markers){
                        if(info.getText().contains(marker)){
                            baselines.put(marker,info.getBaseline().getStartPoint().get(Vector.I2));
                        }
                    }
                }
            });
            for(String marker:markers){
                assertEquals(text,1,occurrences(text,marker));
                assertNotNull("Missing baseline: "+marker,baselines.get(marker));
            }
            assertEquals(18f,baselines.get("MULTIPLE_A")-baselines.get("MULTIPLE_B"),.05f);
            assertEquals(18f,baselines.get("FIXED_A")-baselines.get("FIXED_B"),.05f);
            assertEquals(24f,baselines.get("INHERIT_A")-baselines.get("INHERIT_B"),.05f);
            assertEquals(14.4f,baselines.get("NORMAL_A")-baselines.get("NORMAL_B"),.05f);
            assertEquals(24f,baselines.get("SPAN_A")-baselines.get("SPAN_B"),.05f);
            assertEquals(8f,baselines.get("COMPACT_A")-baselines.get("COMPACT_B"),.05f);
        }finally{
            reader.close();
        }
    }

    private byte[] export(Report report){
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        new PdfProducer().produce(report,output);
        return output.toByteArray();
    }

    private String allText(PdfReader reader) throws Exception {
        StringBuilder text=new StringBuilder();
        for(int page=1;page<=reader.getNumberOfPages();page++) text.append(PdfTextExtractor.getTextFromPage(reader,page));
        return text.toString();
    }

    private int occurrences(String text,String token){
        int count=0,start=0;
        while((start=text.indexOf(token,start))>=0){ count++; start+=token.length(); }
        return count;
    }
}
