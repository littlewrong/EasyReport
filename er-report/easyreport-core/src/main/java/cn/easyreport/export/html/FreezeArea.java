package cn.easyreport.export.html;

import java.util.List;
import java.util.Map;

import cn.easyreport.definition.Expand;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;

/** Resolves a template prefix to the ordinary HTML table, failing closed per axis. */
final class FreezeArea {
	private FreezeArea() {
	}

	static int[] resolve(Report report) {
		int frozenRows=resolveRows(report);
		int frozenColumns=resolveColumns(report);
		if(frozenColumns>0 && !hasSafeColumnPrefix(report,frozenColumns)){
			frozenColumns=0;
		}
		if(frozenRows==0 && frozenColumns==0){
			return new int[]{0,0};
		}
		Map<Row,Map<Column,Cell>> cells=report.getRowColCellMap();
		int visibleRowIndex=0;
		for(Row row:report.getRows()){
			if(!isVisible(row)){
				continue;
			}
			if(visibleRowIndex>=frozenRows){
				break;
			}
			Map<Column,Cell> rowCells=cells.get(row);
			for(Column column:report.getColumns()){
				Cell cell=rowCells==null ? null : rowCells.get(column);
				boolean emitted=cell!=null && !cell.isForPaging();
				if(!emitted){
					continue;
				}
				// HtmlProducer emits rowSpan unchanged. Do not freeze a runtime merge
				// that would cross the resolved DOM boundary after hidden/paging rows.
				if(cell.getExpand()==Expand.Down
						|| (long)visibleRowIndex+Math.max(1,cell.getRowSpan())>frozenRows){
					frozenRows=0;
				}
			}
			visibleRowIndex++;
			if(frozenRows==0 && frozenColumns==0){
				break;
			}
		}
		return new int[]{frozenRows,frozenColumns};
	}

	/**
	 * Resolves the frozen prefix against a single physical page. A later page
	 * freezes rows or columns only when that page actually starts with the
	 * corresponding template prefix (for example, repeated report headers).
	 */
	static int[] resolvePage(Report report,List<Row> rows,List<Column> columns) {
		int frozenRows=resolvePageRows(report.getFreezeRows(),rows);
		int frozenColumns=resolvePageColumns(report.getFreezeColumns(),columns);
		if(frozenColumns>0 && !hasSafeColumnPrefix(report,rows,columns,frozenColumns,true)){
			frozenColumns=0;
		}
		if(frozenRows==0 && frozenColumns==0){
			return new int[]{0,0};
		}
		Map<Row,Map<Column,Cell>> cells=report.getRowColCellMap();
		int visibleRowIndex=0;
		for(Row row:rows){
			if(!isVisibleOnPage(row)){
				continue;
			}
			if(visibleRowIndex>=frozenRows){
				break;
			}
			Map<Column,Cell> rowCells=cells.get(row);
			for(Column column:columns){
				Cell cell=rowCells==null ? null : rowCells.get(column);
				if(cell==null){
					continue;
				}
				int rowSpan=Math.max(1,cell.getPageRowSpan());
				if(cell.getExpand()==Expand.Down || (long)visibleRowIndex+rowSpan>frozenRows){
					frozenRows=0;
				}
			}
			visibleRowIndex++;
			if(frozenRows==0 && frozenColumns==0){
				break;
			}
		}
		return new int[]{frozenRows,frozenColumns};
	}

	private static boolean hasSafeColumnPrefix(Report report,int frozenColumns) {
		return hasSafeColumnPrefix(report,report.getRows(),report.getColumns(),frozenColumns,false);
	}

	private static boolean hasSafeColumnPrefix(Report report,List<Row> rows,List<Column> columns,int frozenColumns,boolean forPage) {
		// Track logical grid slots rather than td indexes: a merged cell may occupy
		// multiple columns and rowspans carry those slots into subsequent HTML rows.
		int[] occupiedRows=new int[frozenColumns];
		boolean hasVisibleRows=false;
		for(Row row:rows){
			if(forPage ? !isVisibleOnPage(row) : !isVisible(row)){
				continue;
			}
			hasVisibleRows=true;
			Map<Column,Cell> rowCells=report.getRowColCellMap().get(row);
			int visibleColumn=-1,domColumn=0;
			for(Column column:columns){
				boolean visible=isVisible(column);
				if(visible){
					visibleColumn++;
				}
				Cell cell=rowCells==null ? null : rowCells.get(column);
				if(cell==null || (!forPage && cell.isForPaging())){
					continue;
				}
				while(domColumn<frozenColumns && occupiedRows[domColumn]>0){
					domColumn++;
				}
				if(domColumn>=frozenColumns){
					// An extra anchor inside an already occupied prefix would be shifted
					// into body columns by the browser, so its source mapping is unsafe.
					if(visible && visibleColumn<frozenColumns){
						return false;
					}
					continue;
				}
				int span=Math.max(1,cell.getColSpan());
				if(!visible || visibleColumn!=domColumn || span>frozenColumns-domColumn
						|| cell.getExpand()==Expand.Right){
					return false;
				}
				for(int slot=domColumn;slot<domColumn+span;slot++){
					if(occupiedRows[slot]>0){
						return false;
					}
					occupiedRows[slot]=Math.max(1,forPage ? cell.getPageRowSpan() : cell.getRowSpan());
				}
				domColumn+=span;
			}
			for(int slot=0;slot<frozenColumns;slot++){
				if(occupiedRows[slot]==0){
					return false;
				}
				occupiedRows[slot]--;
			}
		}
		return hasVisibleRows;
	}

	private static int resolvePageRows(int requested,List<Row> rows) {
		if(requested<=0 || rows.isEmpty()){
			return 0;
		}
		int next=1,visibleFrozen=0,visibleBody=0;
		boolean pastPrefix=false;
		for(Row row:rows){
			int number=row.getTemplateRowNumber();
			if(number<=0){
				return 0;
			}
			if(number<=requested){
				if(pastPrefix || number!=next++){
					return 0;
				}
				if(isVisibleOnPage(row)){
					visibleFrozen++;
				}
			}else{
				pastPrefix=true;
				if(isVisibleOnPage(row)){
					visibleBody++;
				}
			}
		}
		return next==requested+1 && visibleFrozen>0 && visibleBody>0 ? visibleFrozen : 0;
	}

	private static int resolvePageColumns(int requested,List<Column> columns) {
		if(requested<=0 || columns.isEmpty()){
			return 0;
		}
		int next=1,visibleFrozen=0,visibleBody=0;
		boolean pastPrefix=false;
		for(Column column:columns){
			int number=column.getTemplateColumnNumber();
			if(number<=0){
				return 0;
			}
			if(number<=requested){
				if(pastPrefix || number!=next++){
					return 0;
				}
				if(isVisible(column)){
					visibleFrozen++;
				}
			}else{
				pastPrefix=true;
				if(isVisible(column)){
					visibleBody++;
				}
			}
		}
		return next==requested+1 && visibleFrozen>0 && visibleBody>0 ? visibleFrozen : 0;
	}

	private static int resolveRows(Report report) {
		int requested=report.getFreezeRows();
		List<Row> rows=report.getRows();
		if(requested<=0 || requested>=rows.size()){
			return 0;
		}
		int next=1,visibleFrozen=0,visibleBody=0;
		boolean pastPrefix=false;
		for(Row row:rows){
			if(row.isForPaging()){
				continue;
			}
			int number=row.getTemplateRowNumber();
			if(number<=0){
				return 0;
			}
			if(number<=requested){
				// Repeated or reordered template rows mean the selected prefix expanded.
				if(pastPrefix || number!=next++){
					return 0;
				}
				if(isVisible(row)){
					visibleFrozen++;
				}
			}else{
				pastPrefix=true;
				if(isVisible(row)){
					visibleBody++;
				}
			}
		}
		return next==requested+1 && visibleBody>0 ? visibleFrozen : 0;
	}

	private static int resolveColumns(Report report) {
		int requested=report.getFreezeColumns();
		List<Column> columns=report.getColumns();
		if(requested<=0 || requested>=columns.size()){
			return 0;
		}
		int next=1,visibleFrozen=0,visibleBody=0;
		boolean pastPrefix=false;
		for(Column column:columns){
			int number=column.getTemplateColumnNumber();
			if(number<=0){
				return 0;
			}
			if(number<=requested){
				if(pastPrefix || number!=next++){
					return 0;
				}
				if(isVisible(column)){
					visibleFrozen++;
				}
			}else{
				pastPrefix=true;
				if(isVisible(column)){
					visibleBody++;
				}
			}
		}
		return next==requested+1 && visibleBody>0 ? visibleFrozen : 0;
	}

	private static boolean isVisible(Row row) {
		return !row.isForPaging() && row.getRealHeight()>0;
	}

	private static boolean isVisibleOnPage(Row row) {
		return row.getRealHeight()>0;
	}

	private static boolean isVisible(Column column) {
		return !column.isHide() && column.getWidth()>0;
	}
}
