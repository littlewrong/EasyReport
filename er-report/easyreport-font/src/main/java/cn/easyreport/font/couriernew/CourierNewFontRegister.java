package cn.easyreport.font.couriernew;

import cn.easyreport.export.pdf.font.FontRegister;

/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class CourierNewFontRegister implements FontRegister {

	public String getFontName() {
		return "Courier New";
	}

	public String getFontPath() {
		return "cn/easyreport/font/couriernew/COUR.TTF";
	}
}
