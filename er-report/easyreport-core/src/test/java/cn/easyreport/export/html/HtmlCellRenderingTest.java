package cn.easyreport.export.html;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.Test;

import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.build.HideRowColumnBuilder;
import cn.easyreport.build.compute.ExpressionValueCompute;
import cn.easyreport.build.paging.Page;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.PagingMode;
import cn.easyreport.definition.Orientation;
import cn.easyreport.definition.CellStyle;
import cn.easyreport.definition.value.ExpressionValue;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Report;
import cn.easyreport.parser.ReportParser;

public class HtmlCellRenderingTest {
	@Test
	public void oldTextCellsStillEscapeMarkupSpacesAndNewlines() {
		Report report=report(false);
		report.getRootCell().setData("<b>普通 文本</b>\n第二行");
		String html=new HtmlProducer().produce(report);
		assertTrue(html,html.contains("&lt;b&gt;普通&nbsp;文本&lt;/b&gt;<br>第二行"));
		assertFalse(html,html.contains("<div class=\"easyreport-rich-text\">"));
	}

	@Test
	public void simpleCellsRenderSafelyInFullPreviewPagedPreviewAndPrint() {
		Report report=report(true);
		report.getRootCell().setData("<span style='color:red;font-weight:bold'>标题</span><br>正文<script>bad()</script>");
		HtmlProducer producer=new HtmlProducer();
		assertRich(producer.produce(report));
		Page page=report.getPages().get(0);
		assertRich(producer.produceForPagedPreview(report.getContext(),page,false));
		assertRich(producer.produce(report.getContext(),page,true));
		assertRich(producer.produce(report.getContext(),Collections.singletonList(page),10,false));
	}

	@Test
	public void expressionResultsUseHtmlModeAndKeepTheirRawData() {
		Report report=report(true);
		Cell cell=report.getRootCell();
		cell.setValue(new ExpressionValue("\"<strong>表达式结果</strong>\""));
		cell.setData(new ExpressionValueCompute().compute(cell,report.getContext()).get(0).getValue());
		cell.getCellStyle().setWrapCompute(true);
		cell.doCompute(report.getContext());
		assertEquals("<strong>表达式结果</strong>",cell.getData());
		assertEquals(cell.getData(),cell.getFormatData());
		assertTrue(new HtmlProducer().produce(report).contains("<strong>表达式结果</strong>"));
	}

	@Test
	public void cellLinkTakesPriorityWithoutNestedAnchors() {
		Report report=report(true);
		Cell cell=report.getRootCell();
		cell.setData("<a href='https://inner.example'>内部链接</a>");
		cell.setLinkUrl("https://outer.example");
		String html=new HtmlProducer().produce(report);
		assertTrue(html,html.contains("href=\"https://outer.example\""));
		assertFalse(html,html.contains("inner.example"));
		assertEquals(1,html.split("<a ",-1).length-1);
	}

	@Test
	public void reportStyleIncludesScopedRichTextRulesForPrinting() {
		assertTrue(definition(true).getStyle().contains(HtmlCellContent.getStyle()));
	}

	@Test
	public void richTextWidthAndEffectiveLineHeightOverrideHostStyles() {
		Report report=report(true);
		Cell cell=report.getRootCell();
		cell.setData("<p>正文</p>");
		cell.getCellStyle().setLineHeight(1.5f);
		CellStyle customColumn=new CellStyle();
		customColumn.setLineHeight(2f);
		cell.getColumn().setCustomCellStyle(customColumn);
		String html=new HtmlProducer().produce(report);
		assertTrue(html,html.contains("class=\"easyreport-rich-text\" style=\"width:100pt;line-height:2.0;\""));
	}

	@Test
	public void hiddenColumnsDoNotConsumeReducedHtmlMergeSpans() {
		Report report=report(true);
		Cell cell=report.getRootCell();
		for(int i=0;i<2;i++){
			cn.easyreport.model.Column column=new cn.easyreport.model.Column(report.getColumns());
			column.setWidth(100);
			report.getColumns().add(column);
		}
		cell.setColSpan(3);
		cell.setData("<p>合并内容</p>");
		report.getColumn(2).setWidth(0);
		new HideRowColumnBuilder().doHideProcessColumn(report,report.getColumn(2));
		assertEquals(2,cell.getColSpan());
		String html=new HtmlProducer().produce(report);
		assertTrue(html,html.contains("class=\"easyreport-rich-text\" style=\"width:200pt;\""));
		assertTrue(html,html.contains("style=\"width:200pt;\""));
	}

	private void assertRich(String html) {
		assertTrue(html,html.contains("class=\"easyreport-rich-text\""));
		assertTrue(html,html.contains("color:red"));
		assertTrue(html,html.contains("font-weight:bold"));
		assertTrue(html,html.contains("标题</span><br"));
		assertFalse(html,html.contains("&lt;span"));
		assertFalse(html,html.contains("bad()"));
	}

	private Report report(boolean html) {
		Report report=definition(html).newReport();
		Paper paper=new Paper();
		paper.setPagingMode(PagingMode.fitpage);
		paper.setOrientation(Orientation.portrait);
		paper.setWidth(595);
		paper.setHeight(842);
		report.setPaper(paper);
		new Context(null,report,Collections.<String,Dataset>emptyMap(),null,
				Collections.<String,Object>emptyMap(),new HideRowColumnBuilder());
		return report;
	}

	private ReportDefinition definition(boolean html) {
		String xml="<easyreport><row row-number='1' height='20'/><column col-number='1' width='100'/>"
				+"<cell name='A1' row='1' col='1' html='"+html+"'><simple-value>value</simple-value>"
				+"<cell-style font-size='12' font-family='宋体'/></cell></easyreport>";
		return new ReportParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),"html-test");
	}
}
