package cn.easyreport.font.arial;

import cn.easyreport.export.pdf.font.FontRegister;


/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class ArialFontRegister implements FontRegister {

	public String getFontName() {
		return "Arial";
	}

	public String getFontPath() {
		return "cn/easyreport/font/arial/ARIAL.TTF";
	}
}
