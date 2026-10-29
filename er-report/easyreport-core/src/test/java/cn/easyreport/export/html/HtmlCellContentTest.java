package cn.easyreport.export.html;

import static org.junit.Assert.*;

import org.junit.Test;

public class HtmlCellContentTest {
	@Test
	public void keepsCommonRichTextAndInlineStyles() {
		String source="<p align='center'><span style='color:rgb(255,0,0);font-size:18pt;font-weight:bold;"
				+"background-color:#eee;line-height:1.5'>标题</span><br><strong>粗体</strong>"
				+"<em>斜体</em><u>下划线</u><s>删除线</s><sub>下标</sub><sup>上标</sup></p>"
				+"<ol start='2'><li>第一项</li><li>第二项</li></ol>"
				+"<font color='#ff0000' size='4' face='宋体'>旧富文本</font>";
		String html=HtmlCellContent.sanitize(source,true);
		assertTrue(html,html.replace(" ","").contains("color:rgb(255,0,0)"));
		assertTrue(html,html.contains("font-size:18pt"));
		assertTrue(html,html.contains("font-weight:bold"));
		assertTrue(html,html.contains("line-height:1.5"));
		assertTrue(html,html.contains("align=\"center\""));
		assertTrue(html,html.contains("<strong>粗体</strong>"));
		assertTrue(html,html.contains("<li>第二项</li>"));
		assertTrue(html,html.contains("<font"));
	}

	@Test
	public void removesExecutableMarkupExternalResourcesAndPageLayoutStyles() {
		String html=HtmlCellContent.sanitize("<script>alert(1)</script><style>body{display:none}</style>"
				+"<iframe src='https://evil.invalid'></iframe><img src=x onerror=alert(1)>"
				+"<svg onload=alert(1)></svg><table><tr><td>保留文字</td></tr></table>"
				+"<span id='outside' class='danger' onclick=alert(1) style='color:red;position:fixed;"
				+"width:9999px;background-image:url(https://evil.invalid);behavior:url(x);"
				+"font-size:expression(alert(1))'>安全内容</span>",true);
		for(String forbidden:new String[]{"<script","<style","<iframe","<img","<svg","<table",
				"onclick","onerror","alert(","position:","width:","url(","class=","id="}){
			assertFalse(html,html.contains(forbidden));
		}
		assertTrue(html,html.contains("color:red"));
		assertTrue(html,html.contains("安全内容"));
	}

	@Test
	public void restrictsLinksAndCanUnwrapThemForACellLink() {
		String source="<a href='javascript:alert(1)'>危险</a>"
				+"<a href='https://example.com/?a=1&amp;b=2' target='_blank'>正常</a>";
		String html=HtmlCellContent.sanitize(source,true);
		assertFalse(html,html.contains("javascript"));
		assertFalse(html,html.contains("target="));
		assertTrue(html,html.contains("href=\"https://example.com/"));
		html=HtmlCellContent.sanitize(source,false);
		assertFalse(html,html.contains("<a"));
		assertTrue(html,html.contains("正常"));
	}

	@Test
	public void preservesEncodedMarkupWhitespaceAndEmptyCells() {
		String html=HtmlCellContent.sanitize("&lt;b&gt;文字&lt;/b&gt; &amp; 其他",true);
		assertFalse(html,html.contains("<b>"));
		assertTrue(html,html.contains("&lt;b&gt;"));
		assertTrue(html,html.contains(" &amp; "));
		assertTrue(HtmlCellContent.render(null,true).contains("&nbsp;"));
		assertTrue(HtmlCellContent.render("<script>bad()</script>",true).contains("&nbsp;"));
	}
}
