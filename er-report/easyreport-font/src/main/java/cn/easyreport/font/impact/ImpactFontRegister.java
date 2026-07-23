package cn.easyreport.font.impact;

import cn.easyreport.export.pdf.font.FontRegister;

/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class ImpactFontRegister implements FontRegister {

	public String getFontName() {
		return "Impact";
	}

	public String getFontPath() {
		return "cn/easyreport/font/impact/IMPACT.TTF";
	}
}
