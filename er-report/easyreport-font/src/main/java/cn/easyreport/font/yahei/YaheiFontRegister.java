package cn.easyreport.font.yahei;

import cn.easyreport.export.pdf.font.FontRegister;

/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class YaheiFontRegister implements FontRegister {

	public String getFontName() {
		return "微软雅黑";
	}

	public String getFontPath() {
		return "cn/easyreport/font/yahei/msyh.ttc";
	}
}
