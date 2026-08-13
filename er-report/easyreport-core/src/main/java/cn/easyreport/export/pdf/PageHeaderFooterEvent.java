/*******************************************************************************
 * Copyright 2017 Bstek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package cn.easyreport.export.pdf;

import java.net.URL;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.springframework.util.Base64Utils;

import cn.easyreport.build.paging.HeaderFooter;
import cn.easyreport.build.paging.Page;
import cn.easyreport.definition.Orientation;
import cn.easyreport.definition.Paper;
import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.export.pdf.font.FontBuilder;
import cn.easyreport.model.Report;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfWriter;

/**
 * @author Jacky.gao
 * @since 2014年4月22日
 */
public class PageHeaderFooterEvent extends PdfPageEventHelper {
	private Report report;
	private com.itextpdf.text.Image bgImage;
	private float bgLeft;
	private float bgBottom;
	private float bgWidth;
	private float bgHeight;
	public PageHeaderFooterEvent(Report report) {
		this.report=report;
		this.bgImage=buildBackgroundImage(report);
	}
	@Override
	public void onEndPage(PdfWriter writer, Document document) {
		drawBackgroundImage(writer);
		List<Page> pages=report.getPages();
		int pageNumber=writer.getPageNumber();
		if(pageNumber>pages.size()){
			return;
		}
		Page page=pages.get(pageNumber-1);
		HeaderFooter header=page.getHeader();
		HeaderFooter footer=page.getFooter();
		if(header!=null){
			buildTable(writer,header,true,report);
		}
		if(footer!=null){
			buildTable(writer,footer,false,report);
		}
	}
	private com.itextpdf.text.Image buildBackgroundImage(Report report){
		Paper paper=report.getPaper();
		String bgImageUrl=paper.getBgImage();
		if(StringUtils.isBlank(bgImageUrl)){
			return null;
		}
		// Draw the overlay (套打) background into the content area so it matches the HTML preview,
		// where the background is painted on the report <table> within the page margins.
		int pageWidth=paper.getWidth();
		int pageHeight=paper.getHeight();
		if(paper.getOrientation().equals(Orientation.landscape)){
			pageWidth=paper.getHeight();
			pageHeight=paper.getWidth();
		}
		bgLeft=paper.getLeftMargin();
		bgBottom=paper.getBottomMargin();
		bgWidth=pageWidth-paper.getLeftMargin()-paper.getRightMargin();
		bgHeight=pageHeight-paper.getTopMargin()-paper.getBottomMargin();
		try{
			if(bgImageUrl.startsWith("data:")){
				int idx=bgImageUrl.indexOf("base64,");
				if(idx<0){
					return null;
				}
				String base64=bgImageUrl.substring(idx+"base64,".length());
				byte[] bytes=Base64Utils.decodeFromString(base64);
				return com.itextpdf.text.Image.getInstance(bytes);
			}
			return com.itextpdf.text.Image.getInstance(new URL(bgImageUrl));
		}catch(Exception ex){
			// The background image is optional; if it cannot be loaded (bad URL, unreachable host, etc.)
			// skip drawing it rather than aborting the whole PDF export.
			ex.printStackTrace();
			return null;
		}
	}
	private void drawBackgroundImage(PdfWriter writer){
		if(bgImage==null || bgWidth<=0 || bgHeight<=0){
			return;
		}
		try{
			bgImage.scaleAbsolute(bgWidth,bgHeight);
			bgImage.setAbsolutePosition(bgLeft,bgBottom);
			PdfContentByte under=writer.getDirectContentUnder();
			under.addImage(bgImage);
		}catch(DocumentException ex){
			throw new ReportComputeException(ex);
		}
	}
	private void buildTable(PdfWriter writer,HeaderFooter hf,boolean header,Report report) {
		Paper paper=report.getPaper();
		int width=paper.getWidth();
		if(paper.getOrientation().equals(Orientation.landscape)){
			width=paper.getHeight();
		}
		int leftMargin=paper.getLeftMargin();
		int rightMargin=paper.getRightMargin();
		int tableWidth=width-leftMargin-rightMargin;
		int height=paper.getHeight();
		if(paper.getOrientation().equals(Orientation.landscape)){
			height=paper.getWidth();
		}
		int margin=hf.getMargin();
		int hfHeight=hf.getHeight();
		String left=hf.getLeft();
		String center=hf.getCenter();
		String right=hf.getRight();
		try {
			PdfPTable table=null;
			if(StringUtils.isNotEmpty(left)){
				if(StringUtils.isNotEmpty(center) && StringUtils.isNotEmpty(right)){
					table = new PdfPTable(3);
					table.setWidths(new int[]{1, 1, 1});
					table.addCell(buildPdfPCell(hf,left,1));
					table.addCell(buildPdfPCell(hf,center,2));
					table.addCell(buildPdfPCell(hf,right,3));
				}else if(StringUtils.isNotEmpty(center)){
					table = new PdfPTable(3);
					table.setWidths(new int[]{1, 1, 1});
					table.addCell(buildPdfPCell(hf,left,1));
					table.addCell(buildPdfPCell(hf,center,2));
					table.addCell(buildPdfPCell(hf,"",3));
				}else if(StringUtils.isNotEmpty(right)){
					table = new PdfPTable(3);
					table.setWidths(new int[]{1, 1, 1});
					table.addCell(buildPdfPCell(hf,left,1));
					table.addCell(buildPdfPCell(hf,"",2));
					table.addCell(buildPdfPCell(hf,right,3));
				}else{
					table = new PdfPTable(1);
					table.setWidths(new int[]{1});
					table.addCell(buildPdfPCell(hf,left,1));
				}
			}else if(StringUtils.isNotEmpty(center)){
				if(StringUtils.isNotEmpty(right)){
					table = new PdfPTable(3);
					table.setWidths(new int[]{1, 1, 1});
					table.addCell(buildPdfPCell(hf,"",1));
					table.addCell(buildPdfPCell(hf,center,2));
					table.addCell(buildPdfPCell(hf,right,3));
				}else{
					table = new PdfPTable(1);
					table.setWidths(new int[]{1});
					table.addCell(buildPdfPCell(hf,center,2));
				}
			}else if(StringUtils.isNotEmpty(right)){
				table = new PdfPTable(1);
				table.setWidths(new int[]{1});
				table.addCell(buildPdfPCell(hf,right,3));
			}
			if(table==null){
				return;
			}
			table.getDefaultCell().setFixedHeight(hfHeight);
			table.setTotalWidth(tableWidth);
			table.setLockedWidth(true);
		    if(header){
		    	int y=height-margin;
		    	table.writeSelectedRows(0, -1, leftMargin,y, writer.getDirectContent());
		    }else{
		    	table.writeSelectedRows(0, -1, leftMargin,margin+hfHeight, writer.getDirectContent());            	 
		    }
		}catch(DocumentException de) {
		   throw new ReportComputeException(de);
		}
	}
	private PdfPCell buildPdfPCell(HeaderFooter phf,String text,int type){
		PdfPCell cell=new PdfPCell();
		cell.setPadding(0);
		cell.setBorder(Rectangle.NO_BORDER);
		Font font=FontBuilder.getFont(phf.getFontFamily(), phf.getFontSize(), phf.isBold(), phf.isItalic(),phf.isUnderline());
		String fontColor=phf.getForecolor();
		if(StringUtils.isNotEmpty(fontColor)){
			String[] color=fontColor.split(",");
			font.setColor(Integer.valueOf(color[0]), Integer.valueOf(color[1]), Integer.valueOf(color[2]));			
		}
		Paragraph graph=new Paragraph(text,font);
		cell.setPhrase(graph);
		switch(type){
		case 1:
			cell.setHorizontalAlignment(Element.ALIGN_LEFT);
			break;
		case 2:
			cell.setHorizontalAlignment(Element.ALIGN_CENTER);
			break;
		case 3:
			cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
			break;
		}
		cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
		return cell;
	}
}
