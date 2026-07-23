package cn.easyreport.font.comicsansms;

import cn.easyreport.export.pdf.font.FontRegister;

/**
 * @author Jacky.gao
 * @since 2014年5月7日
 */
public class ComicSansMSFontRegister implements FontRegister {

	public String getFontName() {
		return "Comic Sans MS";
	}

	public String getFontPath() {
		return "cn/easyreport/font/comicsansms/COMIC.TTF";
	}
}
