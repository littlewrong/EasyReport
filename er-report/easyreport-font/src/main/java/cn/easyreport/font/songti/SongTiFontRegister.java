package cn.easyreport.font.songti;

import cn.easyreport.export.pdf.font.FontRegister;

/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class SongTiFontRegister implements FontRegister {

	public String getFontName() {
		return "宋体";
	}

	public String getFontPath() {
		return "cn/easyreport/font/songti/SIMSUN.TTC";
	}
}
