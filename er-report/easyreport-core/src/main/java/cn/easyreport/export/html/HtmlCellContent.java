package cn.easyreport.export.html;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.owasp.html.CssSchema;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/** The intentionally limited rich-text dialect shared by preview and printing. */
public final class HtmlCellContent {
	private HtmlCellContent() {
	}

	// Styles are used by every report. Load sanitizer dependencies only for HTML cells.
	private static final class SanitizerPolicy {
		private static final String[] ELEMENTS={"b","strong","i","em","u","s","strike","del",
				"sub","sup","span","br","p","div","ul","ol","li","blockquote",
				"h1","h2","h3","h4","h5","h6","font"};
		private static final CssSchema STYLES=CssSchema.withProperties(Arrays.asList(
				"font-family","font-size","font-weight","font-style","color","background-color",
				"text-decoration","text-align","vertical-align","line-height","text-indent",
				"margin","margin-top","margin-bottom","margin-left","margin-right","rgb()","rgba()","hsl()","hsla()"));
		private static final PolicyFactory WITH_LINKS=policy(true);
		private static final PolicyFactory WITHOUT_LINKS=policy(false);

		private static PolicyFactory policy(boolean allowLinks) {
			HtmlPolicyBuilder builder=new HtmlPolicyBuilder()
					.allowElements(ELEMENTS)
					.allowWithoutAttributes("span","font")
					.allowStyling(STYLES);
			builder.allowAttributes("align").matching(true,"left","center","right","justify")
					.onElements("p","div","h1","h2","h3","h4","h5","h6");
			builder.allowAttributes("color").matching(Pattern.compile("#[0-9a-fA-F]{3,8}|[a-zA-Z]{1,24}"))
					.onElements("font");
			builder.allowAttributes("size").matching(Pattern.compile("[1-7]"))
					.onElements("font");
			builder.allowAttributes("face").matching(Pattern.compile("[\\p{L}\\p{N} _,-]{1,100}"))
					.onElements("font");
			builder.allowAttributes("start").matching(Pattern.compile("[0-9]{1,6}"))
					.onElements("ol");
			if(allowLinks){
				builder.allowElements("a").allowUrlProtocols("http","https","mailto")
						.allowAttributes("href","title").onElements("a");
			}
			return builder.toFactory();
		}
	}

	/** No automatic entity decoding: encoded markup must remain literal text. */
	public static String sanitize(String html,boolean allowLinks) {
		return (allowLinks ? SanitizerPolicy.WITH_LINKS : SanitizerPolicy.WITHOUT_LINKS)
				.sanitize(html==null ? "" : html);
	}

	public static String render(String html,boolean allowLinks) {
		return render(html,allowLinks,0);
	}

	public static String render(String html,boolean allowLinks,int width) {
		return render(html,allowLinks,width,0);
	}

	public static String render(String html,boolean allowLinks,int width,float lineHeight) {
		String content=sanitize(html,allowLinks);
		String style=width>0 ? "width:"+width+"pt;" : "";
		if(Float.isFinite(lineHeight) && lineHeight>0){
			style+="line-height:"+lineHeight+";";
		}
		String size=style.isEmpty() ? "" : " style=\""+style+"\"";
		return "<div class=\"easyreport-rich-text\""+size+">"+(content.isEmpty() ? "&nbsp;" : content)+"</div>";
	}

	/** Included in the report stylesheet, also copied into the print iframe. */
	public static String getStyle() {
		return ".easyreport-rich-text{white-space:normal;word-wrap:break-word;overflow-wrap:anywhere;max-width:100%;line-height:1.2;}"
				+".easyreport-rich-text p,.easyreport-rich-text div{margin:0;}"
				+".easyreport-rich-text ul,.easyreport-rich-text ol{margin:0;padding-left:2em;}"
				+".easyreport-rich-text blockquote{margin:0 0 0 2em;padding:0;border:0;font-size:inherit;}"
				+".easyreport-rich-text h1,.easyreport-rich-text h2,.easyreport-rich-text h3,"
				+".easyreport-rich-text h4,.easyreport-rich-text h5,.easyreport-rich-text h6"
				+"{margin:0;padding:0;font-family:inherit;font-weight:bold;line-height:1.2;color:inherit;}"
				+".easyreport-rich-text h1{font-size:2em;}.easyreport-rich-text h2{font-size:1.5em;}"
				+".easyreport-rich-text h3{font-size:1.17em;}.easyreport-rich-text h4{font-size:1em;}"
				+".easyreport-rich-text h5{font-size:.83em;}.easyreport-rich-text h6{font-size:.67em;}"
				+".easyreport-rich-text a{color:#337ab7;text-decoration:underline;background-color:transparent;}";
	}
}
