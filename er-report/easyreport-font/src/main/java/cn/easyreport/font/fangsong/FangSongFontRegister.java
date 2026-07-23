package cn.easyreport.font.fangsong;

import cn.easyreport.export.pdf.font.FontRegister;

/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class FangSongFontRegister implements FontRegister {

	public String getFontName() {
		return "仿宋";
	}

	public String getFontPath() {
		return "cn/easyreport/font/fangsong/SIMFANG.TTF";
	}
}
