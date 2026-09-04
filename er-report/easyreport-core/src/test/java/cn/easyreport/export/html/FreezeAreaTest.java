package cn.easyreport.export.html;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;

import org.junit.Test;

import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.build.HideRowColumnBuilder;
import cn.easyreport.build.paging.Page;
import cn.easyreport.definition.Expand;
import cn.easyreport.definition.CellDefinition;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;
import cn.easyreport.parser.ReportParser;

public class FreezeAreaTest {
	@Test
	public void readsOptionalAttributesAndKeepsOldTemplatesUnfrozen() {
		ReportDefinition old=definition("");
		assertEquals(0,old.getFreezeRows());
		assertEquals(0,old.getFreezeColumns());
		ReportDefinition configured=definition("freeze-rows='2' freeze-columns='1'");
		assertEquals(2,configured.getFreezeRows());
		assertEquals(1,configured.getFreezeColumns());
		assertArrayEquals(new int[]{2,1},FreezeArea.resolve(configured.newReport()));
	}

	@Test
	public void malformedAndNegativeAttributesFailClosed() {
		ReportDefinition invalid=definition("freeze-rows='-2' freeze-columns='abc'");
		assertEquals(0,invalid.getFreezeRows());
		assertEquals(0,invalid.getFreezeColumns());
		ReportDefinition overflow=definition("freeze-rows='99999999999999' freeze-columns='1.5'");
		assertEquals(0,overflow.getFreezeRows());
		assertEquals(0,overflow.getFreezeColumns());
	}

	@Test
	public void countsVisiblePrefixWithoutFreezingTheFirstBodyRowOrColumn() {
		Report report=report(2,2);
		Row hiddenRow=report.getRow(1);
		hiddenRow.setRealHeight(0);
		Column hiddenColumn=report.getColumn(1);
		hiddenColumn.setWidth(0);
		new HideRowColumnBuilder().doHideProcessColumn(report,hiddenColumn);
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
		String html=new HtmlProducer().produce(report);
		assertTrue(html.contains("data-freeze-rows='1' data-freeze-columns='1'"));
	}

	@Test
	public void repeatedFrozenTemplateRowsDisableOnlyRowFreezing() {
		Report report=report(1,1);
		Row clone=report.getRow(1).newRow();
		assertEquals(1,clone.getTemplateRowNumber());
		report.insertRow(clone,2);
		// Duplicate the row cells so the independent column axis remains well formed.
		report.getRowColCellMap().put(clone,report.getRowColCellMap().get(report.getRow(1)));
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(report));
	}

	@Test
	public void repeatedFrozenTemplateColumnsDisableOnlyColumnFreezing() {
		Report report=report(1,1);
		Column clone=report.getColumn(1).newColumn();
		assertEquals(1,clone.getTemplateColumnNumber());
		report.insertColumn(clone,2);
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(report));
	}

	@Test
	public void expandingBodyDoesNotChangeFrozenHeaderCounts() {
		Report report=report(1,1);
		Row body=report.getRow(2);
		Row clone=body.newRow();
		report.insertRow(clone,3);
		report.getRowColCellMap().put(clone,report.getRowColCellMap().get(body));
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
	}

	@Test
	public void mergedHeadersAllowBothAxesAndColumnBoundaryExtends() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,2,1,2); // B1:C1 lies inside the row band.
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(definition.newReport()));
		definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,2,1,2,1); // A2:A3 lies inside the column band.
		assertEquals(1,definition.newReport().getFreezeRows());
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(definition.newReport()));
		definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,1,1,3);
		assertArrayEquals(new int[]{1,3},FreezeArea.resolve(definition.newReport()));
	}

	@Test
	public void runtimeCompleteMergesAllowBothAxes() {
		Report report=report(1,1);
		cell(report,1,2).setColSpan(2);
		report.getRowColCellMap().get(report.getRow(1)).remove(report.getColumn(3));
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
		report=report(1,1);
		cell(report,2,1).setRowSpan(2);
		report.getRowColCellMap().get(report.getRow(3)).remove(report.getColumn(1));
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
		report=report(1,1);
		cell(report,2,2).setColSpan(2);
		report.getRowColCellMap().get(report.getRow(2)).remove(report.getColumn(3));
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
	}

	@Test
	public void crossingHorizontalMergeExtendsColumnsWithoutSplittingItsCell() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,2,1,1,2);
		assertEquals(1,definition.getFreezeColumns());
		Report report=report(definition);
		assertEquals(2,report.getFreezeColumns());
		assertArrayEquals(new int[]{1,2},FreezeArea.resolve(report));
		String html=new HtmlProducer().produce(report);
		assertTrue(html.contains("data-freeze-columns='2'"));
		assertTrue(html.contains("colspan=\"2\""));
	}

	@Test
	public void chainedHorizontalMergesExtendRegardlessOfCellOrder() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,2,1,1,2);
		merge(definition,3,2,1,2);
		Collections.reverse(definition.getCells());
		Report report=definition.newReport();
		assertEquals(3,report.getFreezeColumns());
		assertArrayEquals(new int[]{1,3},FreezeArea.resolve(report));
	}

	@Test
	public void extendedColumnsRejectRightExpansionAndWholeTableFreezing() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,2,1,1,2);
		definitionCell(definition,1,2).setExpand(Expand.Right);
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(definition.newReport()));
		definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,2,1,1,3);
		merge(definition,3,3,1,2);
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(definition.newReport()));
	}

	@Test
	public void twoDimensionalCornerMergeExtendsBothAxesTogether() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,1,2,2);
		Report report=definition.newReport();
		assertEquals(2,report.getFreezeRows());
		assertEquals(2,report.getFreezeColumns());
		assertArrayEquals(new int[]{2,2},FreezeArea.resolve(report));
	}

	@Test
	public void verticalMergedColumnsUseLogicalSlotsAcrossRows() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='2'");
		merge(definition,2,1,2,1);
		Report report=definition.newReport();
		assertArrayEquals(new int[]{1,2},FreezeArea.resolve(report));
		// B3 is the first emitted td of row 3, but occupies the second logical column.
		report.getRowColCellMap().get(report.getRow(3)).remove(report.getColumn(2));
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(report));
	}

	@Test
	public void runtimeColspanCrossingDisablesOnlyColumnFreezing() {
		Report report=report(1,2);
		cell(report,2,1).setColSpan(2);
		report.getRowColCellMap().get(report.getRow(2)).remove(report.getColumn(2));
		assertArrayEquals(new int[]{1,2},FreezeArea.resolve(report));
		cell(report,2,1).setColSpan(3);
		report.getRowColCellMap().get(report.getRow(2)).remove(report.getColumn(3));
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(report));
	}

	@Test
	public void hiddenMergedColumnsUseTheEmittedSpanAndVisibleBoundary() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='3'");
		merge(definition,2,1,1,3);
		Report report=report(definition);
		report.getColumn(2).setWidth(0);
		new HideRowColumnBuilder().doHideProcessColumn(report,report.getColumn(2));
		assertEquals(2,cell(report,2,1).getColSpan());
		assertArrayEquals(new int[]{1,2},FreezeArea.resolve(report));
		cell(report,2,1).setColSpan(3);
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(report));
	}

	@Test
	public void hiddenMergeAnchorMovesToFirstVisibleFrozenColumn() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='2'");
		merge(definition,2,1,1,2);
		Report report=report(definition);
		report.getColumn(1).setWidth(0);
		new HideRowColumnBuilder().doHideProcessColumn(report,report.getColumn(1));
		assertEquals("A2",cell(report,2,2).getName());
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
	}

	@Test
	public void completeVerticalHeaderMergeKeepsRequestedRows() {
		ReportDefinition definition=definition("freeze-rows='2' freeze-columns='1'");
		merge(definition,1,2,2,1);
		Report report=report(definition);
		assertEquals(2,report.getFreezeRows());
		assertArrayEquals(new int[]{2,1},FreezeArea.resolve(report));
		String html=new HtmlProducer().produce(report);
		assertTrue(html.contains("data-freeze-rows='2'"));
		assertTrue(html.contains("rowspan=\"2\""));
	}

	@Test
	public void crossingVerticalHeaderMergeExtendsRequestedRows() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,2,2,1);
		assertEquals(1,definition.getFreezeRows()); // Preserve the template request.
		Report report=definition.newReport();
		assertEquals(2,report.getFreezeRows());
		assertArrayEquals(new int[]{2,1},FreezeArea.resolve(report));
	}

	@Test
	public void chainedVerticalMergesExtendUntilEveryHeaderMergeIsComplete() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,2,2,1);
		merge(definition,2,3,2,1);
		// XML cell order must not affect the closure.
		Collections.reverse(definition.getCells());
		Report report=definition.newReport();
		assertEquals(3,report.getFreezeRows());
		assertArrayEquals(new int[]{3,1},FreezeArea.resolve(report));
	}

	@Test
	public void extendedHeaderRejectsDownExpansionAndWholeTableFreezing() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,2,2,1);
		definitionCell(definition,2,1).setExpand(Expand.Down);
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(definition.newReport()));
		definition=definition("freeze-rows='1' freeze-columns='1'");
		merge(definition,1,2,3,1);
		merge(definition,3,3,2,1);
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(definition.newReport()));
	}

	@Test
	public void unexpectedRuntimeSpanCrossingDisablesOnlyRowFreezing() {
		Report report=report(2,1);
		cell(report,1,2).setRowSpan(2);
		report.getRowColCellMap().get(report.getRow(2)).remove(report.getColumn(2));
		assertArrayEquals(new int[]{2,1},FreezeArea.resolve(report));
		cell(report,1,2).setRowSpan(3);
		report.getRowColCellMap().get(report.getRow(3)).remove(report.getColumn(2));
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(report));
	}

	@Test
	public void hiddenHeaderRowsUseTheEmittedSpanAndVisibleBoundary() {
		ReportDefinition definition=definition("freeze-rows='3' freeze-columns='1'");
		merge(definition,1,2,3,1);
		Report report=report(definition);
		report.getRow(2).setRealHeight(0);
		new HideRowColumnBuilder().doHideProcessRow(report,report.getRow(2));
		assertEquals(2,cell(report,1,2).getRowSpan());
		assertArrayEquals(new int[]{2,1},FreezeArea.resolve(report));
		// A stale span must not make the hidden row pull body data into the frozen band.
		cell(report,1,2).setRowSpan(3);
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(report));
	}

	@Test
	public void templateExpansionRejectsOnlyTheExpandingAxis() {
		ReportDefinition definition=definition("freeze-rows='1' freeze-columns='1'");
		definition.getCells().get(0).setExpand(Expand.Down);
		assertEquals(0,definition.newReport().getFreezeRows());
		assertEquals(1,definition.newReport().getFreezeColumns());
		definition.getCells().get(0).setExpand(Expand.Right);
		assertEquals(1,definition.newReport().getFreezeRows());
		assertEquals(0,definition.newReport().getFreezeColumns());
	}

	@Test
	public void missingPrefixCellsNeverFreezeTheNextCellInstead() {
		Report report=report(1,1);
		report.getRowColCellMap().get(report.getRow(2)).remove(report.getColumn(1));
		assertArrayEquals(new int[]{1,0},FreezeArea.resolve(report));
	}

	@Test
	public void pagingCopiesAreIgnoredAndPagedOutputHasNoFreezeAttributes() {
		Report report=report(1,1);
		Row clone=report.getRow(1).newRow();
		clone.setForPaging(true);
		report.insertRow(clone,3);
		assertArrayEquals(new int[]{1,1},FreezeArea.resolve(report));
		HtmlProducer producer=new HtmlProducer();
		assertTrue(producer.produce(report).contains("data-easyreport-table='true'"));
		String paged=producer.produce(report.getContext(),new Page(report.getRows(),report.getColumns()),true);
		assertFalse(paged.contains("data-freeze-"));
		assertFalse(paged.contains("data-easyreport-table"));
	}

	@Test
	public void frozenBandMustLeaveVisibleBodyAndHaveCompleteTemplateMapping() {
		Report report=report(1,1);
		for(int i=2;i<=4;i++){
			report.getRow(i).setRealHeight(0);
		}
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(report));
		report=report(1,1);
		report.getRow(1).setTemplateRowNumber(0);
		assertArrayEquals(new int[]{0,1},FreezeArea.resolve(report));
		report=report(4,4);
		assertArrayEquals(new int[]{0,0},FreezeArea.resolve(report));
	}

	private Cell cell(Report report,int row,int column) {
		return report.getRowColCellMap().get(report.getRow(row)).get(report.getColumn(column));
	}

	private Report report(int frozenRows,int frozenColumns) {
		return report(definition("freeze-rows='"+frozenRows+"' freeze-columns='"+frozenColumns+"'"));
	}

	private Report report(ReportDefinition definition) {
		Report report=definition.newReport();
		report.setPaper(new Paper());
		new Context(null,report,Collections.<String,Dataset>emptyMap(),null,
				Collections.<String,Object>emptyMap(),new HideRowColumnBuilder());
		return report;
	}

	private CellDefinition definitionCell(ReportDefinition definition,int row,int column) {
		for(CellDefinition cell:definition.getCells()){
			if(cell.getRowNumber()==row && cell.getColumnNumber()==column){
				return cell;
			}
		}
		throw new AssertionError("Missing fixture cell");
	}

	private void merge(ReportDefinition definition,int row,int column,int rowSpan,int colSpan) {
		CellDefinition merged=definitionCell(definition,row,column);
		merged.setRowSpan(rowSpan);
		merged.setColSpan(colSpan);
		Iterator<CellDefinition> iterator=definition.getCells().iterator();
		while(iterator.hasNext()){
			CellDefinition cell=iterator.next();
			if(cell!=merged && cell.getRowNumber()>=row && cell.getRowNumber()<row+rowSpan
					&& cell.getColumnNumber()>=column && cell.getColumnNumber()<column+colSpan){
				iterator.remove();
			}
		}
	}

	private ReportDefinition definition(String attributes) {
		StringBuilder xml=new StringBuilder("<easyreport "+attributes+">");
		for(int i=1;i<=4;i++){
			xml.append("<row row-number='").append(i).append("' height='20'/>");
			xml.append("<column col-number='").append(i).append("' width='60'/>");
		}
		for(int row=1;row<=4;row++){
			for(int column=1;column<=4;column++){
				xml.append("<cell name='").append((char)('A'+column-1)).append(row)
						.append("' row='").append(row).append("' col='").append(column)
						.append("'><simple-value>value</simple-value></cell>");
			}
		}
		xml.append("</easyreport>");
		return new ReportParser().parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)),"freeze-test");
	}
}
