package cn.easyreport.export.html;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.Test;

import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.build.HideRowColumnBuilder;
import cn.easyreport.build.paging.Page;
import cn.easyreport.definition.Orientation;
import cn.easyreport.definition.PagingMode;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.model.Report;
import cn.easyreport.parser.ReportParser;

public class HtmlCellDependencyIsolationTest {

	@Test
	public void ordinaryReportStylesAndPreviewsDoNotLoadTheHtmlSanitizer() throws Exception {
		try (WithoutSanitizerClassLoader loader=new WithoutSanitizerClassLoader()) {
			invokeScenario(loader,"verifyOrdinaryReport");
			assertEquals("Ordinary reports must not try to load OWASP classes",0,loader.blockedLoads);
		}
	}

	@Test
	public void failedHtmlInitializationDoesNotBreakSubsequentOrdinaryReports() throws Exception {
		try (WithoutSanitizerClassLoader loader=new WithoutSanitizerClassLoader()) {
			invokeScenario(loader,"verifySanitizerIsRequired");
			assertTrue("The isolated runtime must actually reject an OWASP class",loader.blockedLoads>0);
			int attempts=loader.blockedLoads;
			invokeScenario(loader,"verifyOrdinaryReport");
			assertEquals("Ordinary reports must remain independent after HTML initialization fails",
					attempts,loader.blockedLoads);
		}
	}

	private void invokeScenario(ClassLoader loader,String method) throws Exception {
		Class<?> scenario=loader.loadClass(Scenario.class.getName());
		assertSame(loader,scenario.getClassLoader());
		try {
			scenario.getMethod(method).invoke(null);
		} catch (InvocationTargetException exception) {
			Throwable cause=exception.getCause();
			if(cause instanceof Error){
				throw (Error)cause;
			}
			if(cause instanceof Exception){
				throw (Exception)cause;
			}
			throw new AssertionError(cause);
		}
	}

	/** Load a fresh copy of core classes so the normal test classpath cannot hide a missing jar. */
	private static final class WithoutSanitizerClassLoader extends URLClassLoader {
		private int blockedLoads;

		private WithoutSanitizerClassLoader() {
			super(new URL[]{HtmlCellContent.class.getProtectionDomain().getCodeSource().getLocation(),
					HtmlCellDependencyIsolationTest.class.getProtectionDomain().getCodeSource().getLocation()},
					HtmlCellDependencyIsolationTest.class.getClassLoader());
		}

		@Override
		protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
			if(name.startsWith("org.owasp.html.")){
				blockedLoads++;
				throw new ClassNotFoundException("Simulated missing runtime dependency: "+name);
			}
			Class<?> type=findLoadedClass(name);
			if(type==null && name.startsWith("cn.easyreport.")){
				try {
					type=findClass(name);
				} catch (ClassNotFoundException absent) {
					type=super.loadClass(name,false);
				}
			}
			if(type==null){
				type=super.loadClass(name,false);
			}
			if(resolve){
				resolveClass(type);
			}
			return type;
		}
	}

	/** Executed only in the isolated classloader, including its own copy of HtmlCellContent. */
	public static class Scenario {
		public static void verifyOrdinaryReport() {
			String xml="<easyreport><row row-number='1' height='20'/><column col-number='1' width='100'/>"
					+"<cell name='A1' row='1' col='1'><simple-value>value</simple-value>"
					+"<cell-style font-size='12' font-family='Arial'/></cell></easyreport>";
			ReportDefinition definition=new ReportParser().parse(
					new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),"ordinary-report");
			String style=definition.getStyle();
			assertTrue(style,style.contains("._A1{"));
			assertTrue(style,style.contains(HtmlCellContent.getStyle()));

			Report report=definition.newReport();
			Paper paper=new Paper();
			paper.setPagingMode(PagingMode.fitpage);
			paper.setOrientation(Orientation.portrait);
			paper.setWidth(595);
			paper.setHeight(842);
			report.setPaper(paper);
			new Context(null,report,Collections.<String,Dataset>emptyMap(),null,
					Collections.<String,Object>emptyMap(),new HideRowColumnBuilder());
			report.getRootCell().setData("<b>ordinary text</b>\nnext line");
			HtmlProducer producer=new HtmlProducer();
			assertOrdinaryText(producer.produce(report));
			Page page=report.getPages().get(0);
			assertOrdinaryText(producer.produceForPagedPreview(report.getContext(),page,false));
			assertOrdinaryText(producer.produce(report.getContext(),page,true));
		}

		public static void verifySanitizerIsRequired() {
			try {
				HtmlCellContent.sanitize("<script>bad()</script><b>text</b>",true);
				fail("HTML rendering must fail when its sanitizer is unavailable");
			} catch (NoClassDefFoundError expected) {
				// Missing sanitization must never cause raw HTML to be returned.
			}
		}

		private static void assertOrdinaryText(String html) {
			assertTrue(html,html.contains("&lt;b&gt;ordinary&nbsp;text&lt;/b&gt;<br>next&nbsp;line"));
			assertFalse(html,html.contains("class=\"easyreport-rich-text\""));
		}
	}
}
