package cn.easyreport.export.richtext;

import static org.junit.Assert.*;

import org.junit.Test;

import cn.easyreport.definition.CellStyle;
import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.export.html.HtmlCellContent;
import cn.easyreport.export.richtext.RichTextDocument.Paragraph;
import cn.easyreport.export.richtext.RichTextDocument.Run;
import cn.easyreport.model.Cell;

public class RichTextDocumentTest {
    @Test
    public void paragraphsPreserveTextAndInheritedNativeStyles() {
        RichTextDocument doc=parse("<h2 style='text-align:center'>合同标题</h2>"
                +"<p>甲方<strong>加粗<em>斜体</em></strong><u>下划线</u><s>删除</s><sup>上标</sup></p>");
        assertEquals(2,doc.getParagraphs().size());
        Paragraph title=doc.getParagraphs().get(0);
        assertEquals("合同标题",title.getText());
        assertTrue(title.getStyle().isBold());
        assertEquals(18,title.getStyle().getFontSize(),.01);
        assertEquals("center",title.getStyle().getAlign());
        Paragraph body=doc.getParagraphs().get(1);
        assertEquals("甲方加粗斜体下划线删除上标",body.getText());
        assertTrue(body.getRuns().get(2).getStyle().isBold());
        assertTrue(body.getRuns().get(2).getStyle().isItalic());
        assertTrue(body.getRuns().get(3).getStyle().isUnderline());
        assertTrue(body.getRuns().get(4).getStyle().isStrike());
        assertEquals(1,body.getRuns().get(5).getStyle().getScript());
    }

    @Test
    public void contractParagraphIndentMarginsAndLineSpacingSurviveSanitizing() {
        String html="<p style='text-indent:2em;line-height:150%;margin-top:6pt;margin-bottom:8pt;"
                +"text-align:justify'><span style='font-size:16px;color:rgb(255,0,0);"
                +"background-color:#abc;font-family:宋体'>合同正文</span></p>";
        assertTrue(HtmlCellContent.sanitize(html,true).contains("text-indent:2em"));
        Paragraph p=parse(html).getParagraphs().get(0);
        assertEquals(24,p.getStyle().getFirstLineIndent(),.01);
        assertEquals(18,p.getStyle().getLineHeight(),.01);
        assertTrue(p.getStyle().isLineHeightFixed());
        assertEquals(6,p.getStyle().getSpaceBefore(),.01);
        assertEquals(8,p.getStyle().getSpaceAfter(),.01);
        assertEquals("justify",p.getStyle().getAlign());
        Run run=p.getRuns().get(0);
        assertEquals(12,run.getStyle().getFontSize(),.01);
        assertEquals("FF0000",run.getStyle().getColor());
        assertEquals("AABBCC",run.getStyle().getBackgroundColor());
        assertEquals("宋体",run.getStyle().getFontFamily());
    }

    @Test
    public void listMarkersAndNestedParagraphsStayOnTheRightLines() {
        RichTextDocument doc=parse("<ol start='3'><li><p>条款一</p><p>条款补充</p></li>"
                +"<li>条款二<ul><li>子项</li></ul>末尾</li></ol>");
        assertEquals("3. 条款一",doc.getParagraphs().get(0).getText());
        assertEquals("条款补充",doc.getParagraphs().get(1).getText());
        assertEquals("4. 条款二",doc.getParagraphs().get(2).getText());
        assertEquals("• 子项",doc.getParagraphs().get(3).getText());
        assertEquals("末尾",doc.getParagraphs().get(4).getText());
        assertTrue(doc.getParagraphs().get(3).getStyle().getLeftIndent()>
                doc.getParagraphs().get(2).getStyle().getLeftIndent());
    }

    @Test
    public void htmlWhitespaceEntitiesAndExplicitBreaksAreHandledOnce() {
        RichTextDocument doc=parse("\n <div><p>甲  <b>乙</b> 丙<br>丁 &amp; &lt;b&gt;源码&lt;/b&gt;</p></div>\n");
        assertEquals(1,doc.getParagraphs().size());
        assertEquals("甲 乙 丙\n丁 & <b>源码</b>",doc.getParagraphs().get(0).getText());
        assertEquals(1,parse("").getParagraphs().size());
    }

    @Test
    public void scriptsAndResourcesAreNeverParsedOrLoadedAndLinksAreRestricted() {
        RichTextDocument doc=parse("<script>bad()</script><img src='file:///secret'>"
                +"<p onclick='bad()' style='position:fixed;color:blue'>安全<a href='javascript:bad()'>文字</a></p>");
        assertEquals("安全文字",doc.getParagraphs().get(0).getText());
        for(Run run:doc.getParagraphs().get(0).getRuns()){
            assertNull(run.getStyle().getLinkUrl());
        }
        assertEquals("https://example.com",parse("<a href='https://example.com'>链接</a>")
                .getParagraphs().get(0).getRuns().get(0).getStyle().getLinkUrl());
    }

    @Test
    public void originalDataIsUnchangedAndBaseCellStylesAreResolved() {
        Cell cell=cell("<p>正文</p>");
        cell.getCellStyle().setForecolor("12,34,56");
        cell.getCellStyle().setLineHeight(2);
        CellStyle custom=new CellStyle();
        custom.setFontSize(16);
        custom.setBold(true);
        cell.setCustomCellStyle(custom);
        RichTextDocument doc=RichTextDocument.from(cell);
        assertEquals("0C2238",doc.getStyle().getColor());
        assertEquals(16,doc.getStyle().getFontSize(),.01);
        assertTrue(doc.getStyle().isBold());
        assertEquals(2,doc.getStyle().getLineHeight(),.01);
        assertEquals("<p>正文</p>",cell.getFormatData());
    }

    @Test
    public void inlineSizesDoNotReplaceParagraphStyleAndContainerMarginsApplyOnce() {
        RichTextDocument doc=parse("<div style='margin-top:10pt;margin-bottom:20pt'>"
                +"<p><span style='font-size:32pt'>大字</span>正文</p><p>第二段</p></div>");
        assertEquals(2,doc.getParagraphs().size());
        Paragraph first=doc.getParagraphs().get(0),last=doc.getParagraphs().get(1);
        assertEquals(12,first.getStyle().getFontSize(),.01);
        assertEquals(32,first.getRuns().get(0).getStyle().getFontSize(),.01);
        assertEquals(10,first.getStyle().getSpaceBefore(),.01);
        assertEquals(0,first.getStyle().getSpaceAfter(),.01);
        assertEquals(0,last.getStyle().getSpaceBefore(),.01);
        assertEquals(20,last.getStyle().getSpaceAfter(),.01);
    }

    @Test
    public void fixedLengthsAndPercentagesRetainTheirComputedHeightWhenFontSizeChanges() {
        for(String height:new String[]{"24px","18pt","150%","1.5em"}){
            RichTextDocument doc=parse("<div style='font-size:12pt;line-height:"+height+"'>"
                    +"<p style='font-size:18pt'>继承固定行高</p><p style='line-height:2'>覆盖为倍数</p></div>");
            Paragraph inherited=doc.getParagraphs().get(0),overridden=doc.getParagraphs().get(1);
            assertTrue(height,inherited.getStyle().isLineHeightFixed());
            assertEquals(height,18,inherited.getStyle().getLineHeight(),.01);
            assertEquals(18,inherited.getStyle().getFontSize(),.01);
            assertFalse(overridden.getStyle().isLineHeightFixed());
            assertEquals(2,overridden.getStyle().getLineHeight(),.01);
        }
    }

    @Test
    public void normalResetsInheritedHeightWhileUnitlessMultipliersStayRelative() {
        RichTextDocument doc=parse("<div style='line-height:2'>"
                +"<p style='font-size:18pt'>继承倍数</p>"
                +"<p style='line-height:normal'>恢复默认</p>"
                +"<p style='line-height:0.8'>紧凑行距</p></div>");
        assertEquals(2,doc.getParagraphs().get(0).getStyle().getLineHeight(),.01);
        assertFalse(doc.getParagraphs().get(0).getStyle().isLineHeightFixed());
        assertEquals(1.2,doc.getParagraphs().get(1).getStyle().getLineHeight(),.01);
        assertFalse(doc.getParagraphs().get(1).getStyle().isLineHeightFixed());
        assertEquals(.8,doc.getParagraphs().get(2).getStyle().getLineHeight(),.01);
    }

    @Test
    public void explicitInheritanceOverridesHeadingDefaultsAndInitialResetsFixedHeight() {
        RichTextDocument doc=parse("<div style='line-height:24pt'>"
                +"<h2 style='line-height:inherit'>继承</h2><h2 style='line-height:unset'>继承</h2>"
                +"<p style='line-height:initial'>默认</p><p style='line-height:normal'>默认</p></div>");
        for(int index=0;index<2;index++){
            assertTrue(doc.getParagraphs().get(index).getStyle().isLineHeightFixed());
            assertEquals(24,doc.getParagraphs().get(index).getStyle().getLineHeight(),.01);
        }
        for(int index=2;index<4;index++){
            assertFalse(doc.getParagraphs().get(index).getStyle().isLineHeightFixed());
            assertEquals(1.2,doc.getParagraphs().get(index).getStyle().getLineHeight(),.01);
        }
    }

    @Test
    public void uniformInlineHeightAppliesToTheWholeParagraphWithoutReplacingOtherStyles() {
        RichTextDocument doc=parse("<p style='text-indent:24pt;margin-bottom:6pt'> "
                +"<span style='font-size:16pt;line-height:2'>甲<strong>乙</strong></span>"
                +"<br><span style='line-height:2'>丙</span> </p>"
                +"<span style='line-height:24px'>根节点<br><b>第二行</b></span>");
        Paragraph first=doc.getParagraphs().get(0),second=doc.getParagraphs().get(1);
        assertEquals(2,first.getStyle().getLineHeight(),.01);
        assertFalse(first.getStyle().isLineHeightFixed());
        assertEquals(12,first.getStyle().getFontSize(),.01);
        assertEquals(24,first.getStyle().getFirstLineIndent(),.01);
        assertEquals(6,first.getStyle().getSpaceAfter(),.01);
        assertEquals("甲乙\n丙",first.getText());
        assertTrue(second.getStyle().isLineHeightFixed());
        assertEquals(18,second.getStyle().getLineHeight(),.01);
    }

    @Test
    public void mixedLocalHeightsAreNotAppliedToUnrelatedTextOrFollowingParagraphs() {
        RichTextDocument doc=parse("<p><span style='line-height:3'>局部</span>正文</p>"
                +"<p><span style='line-height:2'>甲</span><span style='line-height:3'>乙</span></p>"
                +"<p><span style='line-height:2'>整段</span></p><p>后续正文</p>");
        assertEquals(1.2,doc.getParagraphs().get(0).getStyle().getLineHeight(),.01);
        assertEquals(1.2,doc.getParagraphs().get(1).getStyle().getLineHeight(),.01);
        assertEquals(2,doc.getParagraphs().get(2).getStyle().getLineHeight(),.01);
        assertEquals(1.2,doc.getParagraphs().get(3).getStyle().getLineHeight(),.01);
    }

    @Test
    public void uniformInlineHeightCannotShrinkTheEnclosingParagraphOrDuplicateItsMargins() {
        RichTextDocument doc=parse("<p style='line-height:2'><span style='line-height:1'>甲<br>乙</span></p>"
                +"<div style='margin-top:10pt;margin-bottom:20pt'>"
                +"<p style='margin-top:6pt;margin-bottom:8pt'><span style='line-height:2'>正文</span></p></div>");
        assertEquals(2,doc.getParagraphs().get(0).getStyle().getLineHeight(),.01);
        Paragraph promoted=doc.getParagraphs().get(1);
        assertEquals(2,promoted.getStyle().getLineHeight(),.01);
        assertEquals(16,promoted.getStyle().getSpaceBefore(),.01);
        assertEquals(28,promoted.getStyle().getSpaceAfter(),.01);
    }

    @Test
    public void invalidOrNonPositiveHeightsKeepTheInheritedValue() {
        for(String height:new String[]{"0","-2","bogus","calc(1em + 2px)"}){
            Paragraph paragraph=parse("<div style='line-height:2'><p style='line-height:"+height+"'>正文</p></div>")
                    .getParagraphs().get(0);
            assertFalse(height,paragraph.getStyle().isLineHeightFixed());
            assertEquals(height,2,paragraph.getStyle().getLineHeight(),.01);
        }
    }

    @Test
    public void emptyEditorParagraphsUseTheirUniformBreakHeight() {
        RichTextDocument doc=parse("<p><span style='line-height:2'><br></span></p>"
                +"<p><span style='line-height:24px'><br><br></span></p><p>正文</p>");
        assertEquals(2,doc.getParagraphs().get(0).getStyle().getLineHeight(),.01);
        assertTrue(doc.getParagraphs().get(1).getStyle().isLineHeightFixed());
        assertEquals(18,doc.getParagraphs().get(1).getStyle().getLineHeight(),.01);
        assertEquals(1.2,doc.getParagraphs().get(2).getStyle().getLineHeight(),.01);
    }

    @Test
    public void listItemsInheritHeightsAndHeadingsKeepThePreviewDefaultUnlessOverridden() {
        RichTextDocument doc=parse("<div style='line-height:24px'><ul>"
                +"<li>甲</li><li style='line-height:normal'>乙</li></ul>"
                +"<h2>标题</h2><h2 style='line-height:24pt'>指定标题</h2></div>");
        assertTrue(doc.getParagraphs().get(0).getStyle().isLineHeightFixed());
        assertEquals(18,doc.getParagraphs().get(0).getStyle().getLineHeight(),.01);
        assertFalse(doc.getParagraphs().get(1).getStyle().isLineHeightFixed());
        assertEquals(1.2,doc.getParagraphs().get(1).getStyle().getLineHeight(),.01);
        assertFalse(doc.getParagraphs().get(2).getStyle().isLineHeightFixed());
        assertEquals(1.2,doc.getParagraphs().get(2).getStyle().getLineHeight(),.01);
        assertTrue(doc.getParagraphs().get(3).getStyle().isLineHeightFixed());
        assertEquals(24,doc.getParagraphs().get(3).getStyle().getLineHeight(),.01);
    }

    private RichTextDocument parse(String html) { return RichTextDocument.from(cell(html)); }
    private Cell cell(String html) {
        Cell cell=new Cell();
        CellStyle style=new CellStyle();
        style.setFontFamily("宋体"); style.setFontSize(12);
        cell.setCellStyle(style); cell.setValue(new SimpleValue(html));
        cell.setData(html); cell.setHtml(true);
        return cell;
    }
}
