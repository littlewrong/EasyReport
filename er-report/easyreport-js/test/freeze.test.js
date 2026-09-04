// Run with: node test/freeze.test.js (no browser or new dependencies required).
require('babel-register')({presets:[['env',{targets:{node:'current'}}]],babelrc:false});
const assert=require('assert');
const {freezeCount,expandFreezeRows,expandFreezeColumns,freezeProblem,normalizeFreeze,shiftFreeze,expansionAllowed}=require('../src/table/FreezeRules.js');
const {applyFreeze,getFreeze,setFreeze,canMerge,canUnmerge,canExpand,guardNativeMerge}=require('../src/table/FreezeUtils.js');
const {undoManager,buildNewCellDef,tableToXml}=require('../src/Utils.js');
const Context=require('../src/Context.js').default;
const MergeTool=require('../src/tools/MergeTool.js').default;
const {doInsertRow}=require('../src/table/operation/InsertRowOperation.js');
const {doInsertCol}=require('../src/table/operation/InsertColOperation.js');
const {doDeleteRow}=require('../src/table/operation/DeleteRowOperation.js');
const {doDeleteCol}=require('../src/table/operation/DeleteColOperation.js');
global.window={i18n:require('../src/i18n/designer.json')};
// The operations use jQuery only for dirty-state UI; rejected actions use MsgBox.
global.$=()=>({removeClass(){},append(){},on(){},modal(){}});

for(const invalid of [-1,1.5,'oops',Infinity,undefined]) assert.strictEqual(freezeCount(invalid),0);
assert.strictEqual(freezeCount('3'),3);
assert.deepStrictEqual(normalizeFreeze({},6,6,[],[]),{freezeRows:0,freezeColumns:0});
assert.strictEqual(freezeProblem('rows',6,6,6,[],[]),'remaining');
assert.strictEqual(freezeProblem('columns',6,6,6,[],[]),'remaining');
// Row freezes include complete merged headers. Resolve all intersecting row
// spans even when their storage order requires more than one expansion pass.
const mergedHeaders=[
    {row:2,col:4,rowspan:3,colspan:1},
    {row:0,col:3,rowspan:3,colspan:1},
    {row:4,col:2,rowspan:1,colspan:2}
];
assert.strictEqual(expandFreezeRows(1,mergedHeaders),5);
assert.strictEqual(expandFreezeRows(1,mergedHeaders.slice().reverse()),5);
assert.strictEqual(expandFreezeRows(5,mergedHeaders),5);
assert.strictEqual(expandFreezeRows(0,mergedHeaders),0);
assert.strictEqual(expandFreezeRows(2,[{row:2,col:0,rowspan:2,colspan:2}]),2);
assert.strictEqual(expandFreezeRows(2,[{row:0,col:0,rowspan:1,colspan:6}]),2);
assert.strictEqual(freezeProblem('rows',2,6,6,[{row:0,col:4,rowspan:1,colspan:2}],[]),null);
assert.strictEqual(freezeProblem('rows',1,6,6,mergedHeaders,[]),null);
assert.strictEqual(freezeProblem('rows',1,6,6,[{row:0,col:3,rowspan:6,colspan:1}],[]),'remaining');
assert.strictEqual(freezeProblem('rows',1,6,6,mergedHeaders,[{rowNumber:5,columnNumber:1,expand:'Down'}]),'expanding');
assert.strictEqual(freezeProblem('rows',1,6,6,mergedHeaders,[{rowNumber:6,columnNumber:1,expand:'Down'}]),null);
assert.deepStrictEqual(normalizeFreeze({freezeRows:1,freezeColumns:2},6,6,mergedHeaders,[]),{freezeRows:5,freezeColumns:2});
assert.deepStrictEqual(normalizeFreeze({freezeRows:1,freezeColumns:2},6,6,mergedHeaders,[{rowNumber:5,columnNumber:1,expand:'Down'}]),{freezeRows:0,freezeColumns:2});
assert.deepStrictEqual(normalizeFreeze({freezeRows:1,freezeColumns:2},6,6,[{row:0,col:3,rowspan:6,colspan:1}],[]),{freezeRows:0,freezeColumns:2});
// Column freezes follow the same rule, extending rightward until every
// intersecting horizontal merge is contained, independently of merge order.
const mergedColumnHeaders=mergedHeaders.map(merge=>({row:merge.col,col:merge.row,rowspan:merge.colspan,colspan:merge.rowspan}));
assert.strictEqual(expandFreezeColumns(1,mergedColumnHeaders),5);
assert.strictEqual(expandFreezeColumns(1,mergedColumnHeaders.slice().reverse()),5);
assert.strictEqual(expandFreezeColumns(5,mergedColumnHeaders),5);
assert.strictEqual(expandFreezeColumns(0,mergedColumnHeaders),0);
assert.strictEqual(expandFreezeColumns(2,[{row:0,col:2,rowspan:2,colspan:2}]),2);
assert.strictEqual(expandFreezeColumns(2,[{row:0,col:0,rowspan:6,colspan:1}]),2);
assert.strictEqual(freezeProblem('columns',2,6,6,[{row:4,col:0,rowspan:2,colspan:1}],[]),null);
assert.strictEqual(freezeProblem('columns',2,6,6,[{row:0,col:0,rowspan:1,colspan:2}],[]),null);
assert.strictEqual(freezeProblem('columns',2,6,6,[{row:0,col:1,rowspan:1,colspan:2}],[]),null);
assert.strictEqual(freezeProblem('columns',2,6,6,[{row:0,col:2,rowspan:2,colspan:2}],[]),null);
assert.strictEqual(freezeProblem('columns',1,6,6,mergedColumnHeaders,[]),null);
assert.strictEqual(freezeProblem('columns',1,6,6,[{row:3,col:0,rowspan:1,colspan:6}],[]),'remaining');
assert.strictEqual(freezeProblem('columns',1,6,6,mergedColumnHeaders,[{rowNumber:1,columnNumber:5,expand:'Right'}]),'expanding');
assert.strictEqual(freezeProblem('columns',1,6,6,mergedColumnHeaders,[{rowNumber:1,columnNumber:6,expand:'Right'}]),null);
assert.strictEqual(freezeProblem('columns',1,6,6,mergedColumnHeaders,[{rowNumber:1,columnNumber:1,expand:'Down'}]),null);
assert.deepStrictEqual(normalizeFreeze({freezeRows:2,freezeColumns:1},6,6,mergedColumnHeaders,[]),{freezeRows:2,freezeColumns:5});
assert.deepStrictEqual(normalizeFreeze({freezeRows:2,freezeColumns:1},6,6,mergedColumnHeaders,[{rowNumber:1,columnNumber:5,expand:'Right'}]),{freezeRows:2,freezeColumns:0});
assert.deepStrictEqual(normalizeFreeze({freezeRows:2,freezeColumns:1},6,6,[{row:3,col:0,rowspan:1,colspan:6}],[]),{freezeRows:2,freezeColumns:0});
assert.strictEqual(freezeProblem('rows',2,6,6,[{row:2,col:0,rowspan:2,colspan:2}],[]),null);
assert.strictEqual(freezeProblem('rows',2,6,6,[],[{rowNumber:2,columnNumber:5,expand:'Down'}]),'expanding');
assert.strictEqual(freezeProblem('columns',2,6,6,[],[{rowNumber:5,columnNumber:2,expand:'Right'}]),'expanding');
assert.strictEqual(freezeProblem('rows',2,6,6,[],[{rowNumber:1,columnNumber:1,expand:'Right'}]),null);
// Covered cells are absent from the saved report. Their stale expansion
// metadata must not veto a header freeze; its visible anchor still must.
const horizontalHeader={row:0,col:0,rowspan:1,colspan:2};
const verticalHeader={row:0,col:0,rowspan:3,colspan:1};
const horizontalCells=[{rowNumber:1,columnNumber:1,expand:'None'},{rowNumber:1,columnNumber:2,expand:'Down'}];
const verticalCells=[{rowNumber:1,columnNumber:1,expand:'None'},{rowNumber:2,columnNumber:1,expand:'Down'},{rowNumber:3,columnNumber:1,expand:'Down'}];
assert.strictEqual(freezeProblem('rows',1,6,6,[horizontalHeader],horizontalCells),null);
assert.strictEqual(freezeProblem('rows',1,6,6,[verticalHeader],verticalCells),null);
assert.strictEqual(freezeProblem('rows',1,6,6,[horizontalHeader],[{rowNumber:1,columnNumber:1,expand:'Down'}]),'expanding');
assert.strictEqual(freezeProblem('rows',1,6,6,[verticalHeader],[{rowNumber:1,columnNumber:1,expand:'Down'}]),'expanding');
assert.strictEqual(freezeProblem('rows',1,6,6,[horizontalHeader],horizontalCells.concat({rowNumber:1,columnNumber:3,expand:'Down'})),'expanding');
assert.strictEqual(freezeProblem('rows',1,6,6,[verticalHeader],verticalCells.concat({rowNumber:2,columnNumber:2,expand:'Down'})),'expanding');
assert.strictEqual(freezeProblem('rows',1,6,6,[],horizontalCells),'expanding');
assert.strictEqual(freezeProblem('columns',1,6,6,[horizontalHeader],[{rowNumber:1,columnNumber:2,expand:'Right'}]),null);
assert.strictEqual(freezeProblem('columns',1,6,6,[verticalHeader],[{rowNumber:2,columnNumber:1,expand:'Right'}]),null);
assert.strictEqual(freezeProblem('columns',1,6,6,[horizontalHeader],[{rowNumber:1,columnNumber:1,expand:'Right'}]),'expanding');
assert.strictEqual(freezeProblem('columns',1,6,6,[verticalHeader],[{rowNumber:1,columnNumber:1,expand:'Right'}]),'expanding');
assert.strictEqual(freezeProblem('columns',1,6,6,[horizontalHeader],[{rowNumber:2,columnNumber:1,expand:'Right'}]),'expanding');
assert.strictEqual(freezeProblem('columns',1,6,6,[verticalHeader],[{rowNumber:4,columnNumber:1,expand:'Right'}]),'expanding');
assert.strictEqual(freezeProblem('columns',2,6,6,[],[{rowNumber:1,columnNumber:2,expand:'Right'}]),'expanding');
assert.deepStrictEqual(normalizeFreeze({freezeRows:1,freezeColumns:0},6,6,[verticalHeader],verticalCells),{freezeRows:3,freezeColumns:0});
assert.deepStrictEqual(normalizeFreeze({freezeRows:0,freezeColumns:1},6,6,[horizontalHeader],[{rowNumber:1,columnNumber:2,expand:'Right'}]),{freezeRows:0,freezeColumns:2});
const original={freezeRows:3,freezeColumns:2};
assert.deepStrictEqual(shiftFreeze(original,'rows',1,2,true),{freezeRows:5,freezeColumns:2});
assert.deepStrictEqual(shiftFreeze(original,'rows',3,1,true),original);
assert.deepStrictEqual(shiftFreeze(original,'columns',1,3,false),{freezeRows:3,freezeColumns:1});
assert.deepStrictEqual(shiftFreeze(original,'rows',0,4,false),{freezeRows:0,freezeColumns:2});
assert.deepStrictEqual(shiftFreeze(original,'rows',5,1,false),original);
assert.strictEqual(expansionAllowed(original,'Down',4,0,2,0),false);
assert.strictEqual(expansionAllowed(original,'Right',0,3,0,1),false);
assert.strictEqual(expansionAllowed(original,'None',0,0,0,0),true);

class Table {
    constructor(){
        this.data=Array.from({length:6},()=>Array(6).fill(''));
        this.settings={rowHeights:Array(6).fill(25),colWidths:Array(6).fill(98),mergeCells:[],fixedRowsTop:0,fixedColumnsLeft:0};
        this.rootElement={classList:{toggle(){}}};
        this.context=new Context({hot:this,reportDef:{paper:{},datasources:[]},cellsMap:new Map()});
        for(let row=1;row<=6;row++) for(let col=1;col<=6;col++) this.context.addCell(buildNewCellDef(row,col));
        this.selected=[1,1,1,1];
    }
    getSettings(){return this.settings;}
    updateSettings(settings){Object.assign(this.settings,settings);}
    countRows(){return this.data.length;}
    countCols(){return this.data[0].length;}
    getSelected(){return this.selected;}
    getRowHeight(row){return this.settings.rowHeights[row];}
    getColWidth(col){return this.settings.colWidths[col];}
    getCell(){return {};}
    loadData(data){this.data=data;}
    alter(action,position){
        if(action==='insert_row') this.data.splice(position,0,Array(this.countCols()).fill(''));
        if(action==='remove_row'){
            this.data.splice(position,1);
            if(position<this.settings.fixedRowsTop) this.settings.fixedRowsTop--;
        }
        if(action==='insert_col') this.data.forEach(row=>row.splice(position,0,''));
        if(action==='remove_col'){
            this.data.forEach(row=>row.splice(position,1));
            if(position<this.settings.fixedColumnsLeft) this.settings.fixedColumnsLeft--;
        }
    }
}

// Exercise the actual operations and their custom undo closures, rather than
// merely testing the boundary arithmetic independently of integration.
for(const [operation,selection,expected] of [
    [doInsertRow,[1,1,1,1],{freezeRows:4,freezeColumns:2}],
    [doInsertCol,[1,1,1,1],{freezeRows:3,freezeColumns:3}],
    [doInsertRow,[3,1,3,1],original],
    [doInsertCol,[1,2,1,2],original],
    [doDeleteRow,[1,1,3,1],{freezeRows:1,freezeColumns:2}],
    [doDeleteCol,[1,1,1,3],{freezeRows:3,freezeColumns:1}]
]){
    undoManager.clear();
    const table=new Table();
    table.selected=selection;
    applyFreeze(table,original);
    operation.call(table,true);
    assert.deepStrictEqual(getFreeze(table),expected);
    assert.strictEqual(table.context.reportDef.freezeRows,expected.freezeRows);
    undoManager.undo();
    assert.deepStrictEqual(getFreeze(table),original);
    assert.strictEqual(table.countRows(),6);
    assert.strictEqual(table.countCols(),6);
    undoManager.redo();
    assert.deepStrictEqual(getFreeze(table),expected);
}

// Setting, serialization and loading agree on the expanded row boundary,
// including undo/redo. Rejected requests leave the prior state untouched.
undoManager.clear();
const headerTable=new Table();
headerTable.settings.mergeCells=mergedHeaders.map(merge=>Object.assign({},merge));
assert.strictEqual(setFreeze(headerTable,{freezeRows:1,freezeColumns:2}),true);
const headerState={freezeRows:5,freezeColumns:2};
assert.deepStrictEqual(getFreeze(headerTable),headerState);
assert.strictEqual(headerTable.context.reportDef.freezeRows,5);
assert(decodeURIComponent(tableToXml(headerTable.context)).includes('<easyreport freeze-rows="5" freeze-columns="2">'));
// Another requested boundary inside the same merged header is the same state
// and must not consume an extra undo step.
assert.strictEqual(setFreeze(headerTable,{freezeRows:2,freezeColumns:2}),true);
undoManager.undo();
assert.deepStrictEqual(getFreeze(headerTable),{freezeRows:0,freezeColumns:0});
undoManager.redo();
assert.deepStrictEqual(getFreeze(headerTable),headerState);
applyFreeze(headerTable,{freezeRows:1,freezeColumns:2});
assert.deepStrictEqual(getFreeze(headerTable),headerState);
applyFreeze(headerTable,{});
assert.deepStrictEqual(getFreeze(headerTable),{freezeRows:0,freezeColumns:0});
applyFreeze(headerTable,{freezeRows:0,freezeColumns:2});
headerTable.context.getCell(4,0).expand='Down';
assert.strictEqual(setFreeze(headerTable,{freezeRows:1,freezeColumns:2}),false);
assert.deepStrictEqual(getFreeze(headerTable),{freezeRows:0,freezeColumns:2});
assert.strictEqual(headerTable.context.reportDef.freezeRows,0);
headerTable.context.getCell(4,0).expand='None';
headerTable.settings.mergeCells=[{row:0,col:3,rowspan:6,colspan:1}];
assert.strictEqual(setFreeze(headerTable,{freezeRows:1,freezeColumns:2}),false);
assert.deepStrictEqual(getFreeze(headerTable),{freezeRows:0,freezeColumns:2});
headerTable.settings.mergeCells=[{row:0,col:0,rowspan:1,colspan:2}];
assert.strictEqual(setFreeze(headerTable,{freezeRows:2,freezeColumns:2}),true);
assert.deepStrictEqual(getFreeze(headerTable),{freezeRows:2,freezeColumns:2});

undoManager.clear();
const columnHeaderTable=new Table();
columnHeaderTable.settings.mergeCells=mergedColumnHeaders.map(merge=>Object.assign({},merge));
assert.strictEqual(setFreeze(columnHeaderTable,{freezeRows:2,freezeColumns:1}),true);
const columnHeaderState={freezeRows:2,freezeColumns:5};
assert.deepStrictEqual(getFreeze(columnHeaderTable),columnHeaderState);
assert.strictEqual(columnHeaderTable.context.reportDef.freezeColumns,5);
assert(decodeURIComponent(tableToXml(columnHeaderTable.context)).includes('<easyreport freeze-rows="2" freeze-columns="5">'));
assert.strictEqual(setFreeze(columnHeaderTable,{freezeRows:2,freezeColumns:2}),true);
undoManager.undo();
assert.deepStrictEqual(getFreeze(columnHeaderTable),{freezeRows:0,freezeColumns:0});
undoManager.redo();
assert.deepStrictEqual(getFreeze(columnHeaderTable),columnHeaderState);
applyFreeze(columnHeaderTable,{freezeRows:2,freezeColumns:1});
assert.deepStrictEqual(getFreeze(columnHeaderTable),columnHeaderState);
applyFreeze(columnHeaderTable,{});
assert.deepStrictEqual(getFreeze(columnHeaderTable),{freezeRows:0,freezeColumns:0});
applyFreeze(columnHeaderTable,{freezeRows:2,freezeColumns:0});
columnHeaderTable.context.getCell(0,4).expand='Right';
assert.strictEqual(setFreeze(columnHeaderTable,{freezeRows:2,freezeColumns:1}),false);
assert.deepStrictEqual(getFreeze(columnHeaderTable),{freezeRows:2,freezeColumns:0});
assert.strictEqual(columnHeaderTable.context.reportDef.freezeColumns,0);
columnHeaderTable.context.getCell(0,4).expand='None';
columnHeaderTable.settings.mergeCells=[{row:3,col:0,rowspan:1,colspan:6}];
assert.strictEqual(setFreeze(columnHeaderTable,{freezeRows:2,freezeColumns:1}),false);
assert.deepStrictEqual(getFreeze(columnHeaderTable),{freezeRows:2,freezeColumns:0});

// Freeze with hidden Down/Right cells, then reload only the cells emitted
// by tableToXml. Both representations must keep the same effective boundary.
for(const [merge,hiddenCells,expand,requested,expected] of [
    [horizontalHeader,[[0,1]],'Down',{freezeRows:1,freezeColumns:0},{freezeRows:1,freezeColumns:0}],
    [verticalHeader,[[1,0],[2,0]],'Down',{freezeRows:1,freezeColumns:0},{freezeRows:3,freezeColumns:0}],
    [horizontalHeader,[[0,1]],'Right',{freezeRows:0,freezeColumns:1},{freezeRows:0,freezeColumns:2}],
    [verticalHeader,[[1,0],[2,0]],'Right',{freezeRows:0,freezeColumns:1},{freezeRows:0,freezeColumns:1}]
]){
    undoManager.clear();
    const mergedTable=new Table();
    mergedTable.settings.mergeCells=[Object.assign({},merge)];
    for(const [row,col] of hiddenCells) mergedTable.context.getCell(row,col).expand=expand;
    assert.strictEqual(setFreeze(mergedTable,requested),true);
    assert.deepStrictEqual(getFreeze(mergedTable),expected);
    const savedXml=decodeURIComponent(tableToXml(mergedTable.context));
    assert(savedXml.includes('<easyreport freeze-rows="'+expected.freezeRows+'" freeze-columns="'+expected.freezeColumns+'">'));
    assert(savedXml.includes('name="A1" row="1" col="1"'));
    for(const [row,col] of hiddenCells){
        assert(!savedXml.includes('name="'+mergedTable.context.getCellName(row,col)+'"'));
        // Saving must ignore hidden cells without altering their in-memory data.
        assert.strictEqual(mergedTable.context.getCell(row,col).expand,expand);
    }
    const loadedTable=new Table();
    loadedTable.context.cellsMap.clear();
    const attribute=(tag,name)=>{
        const match=tag.match(new RegExp('\\b'+name+'="([^"]*)"'));
        return match ? match[1] : undefined;
    };
    for(const tag of savedXml.match(/<cell\s[^>]*>/g)){
        const row=Number(attribute(tag,'row')),col=Number(attribute(tag,'col'));
        const cell=buildNewCellDef(row,col);
        cell.expand=attribute(tag,'expand');
        loadedTable.context.addCell(cell);
        const rowspan=Number(attribute(tag,'row-span')) || 1,colspan=Number(attribute(tag,'col-span')) || 1;
        if(rowspan>1 || colspan>1) loadedTable.settings.mergeCells.push({row:row-1,col:col-1,rowspan,colspan});
    }
    for(const [row,col] of hiddenCells) assert.strictEqual(loadedTable.context.getCell(row,col),undefined);
    applyFreeze(loadedTable,{freezeRows:Number(attribute(savedXml,'freeze-rows')),freezeColumns:Number(attribute(savedXml,'freeze-columns'))});
    assert.deepStrictEqual(getFreeze(loadedTable),expected);
    assert.strictEqual(loadedTable.context.reportDef.freezeRows,mergedTable.context.reportDef.freezeRows);
    assert.strictEqual(loadedTable.context.reportDef.freezeColumns,mergedTable.context.reportDef.freezeColumns);
    applyFreeze(mergedTable,{freezeRows:0,freezeColumns:0});
    mergedTable.context.getCell(0,0).expand=expand;
    assert.strictEqual(setFreeze(mergedTable,requested),false);
    assert.deepStrictEqual(getFreeze(mergedTable),{freezeRows:0,freezeColumns:0});
}

// Splitting must not expose a hidden Down cell inside an existing row freeze.
// Keeping the merge that covers it while splitting an unrelated header is safe.
const otherHeader={row:0,col:3,rowspan:1,colspan:2};
const splitTable=new Table();
splitTable.settings.mergeCells=[Object.assign({},horizontalHeader),Object.assign({},otherHeader)];
splitTable.context.getCell(0,1).expand='Down';
applyFreeze(splitTable,{freezeRows:1,freezeColumns:0});
assert.strictEqual(canUnmerge(splitTable,[]),false);
assert.strictEqual(canUnmerge(splitTable,[otherHeader]),false);
assert.strictEqual(canUnmerge(splitTable,[horizontalHeader]),true);
undoManager.clear();
const mergeTool=new MergeTool(splitTable.context);
splitTable.selected=[0,3,0,4];
mergeTool.execute();
assert.deepStrictEqual(splitTable.settings.mergeCells,[horizontalHeader]);
assert.strictEqual(getFreeze(splitTable).freezeRows,1);
undoManager.undo();
assert.deepStrictEqual(splitTable.settings.mergeCells,[horizontalHeader,otherHeader]);
undoManager.redo();
assert.deepStrictEqual(splitTable.settings.mergeCells,[horizontalHeader]);
undoManager.clear();
splitTable.selected=[0,1,0,0];
mergeTool.execute();
assert.deepStrictEqual(splitTable.settings.mergeCells,[horizontalHeader]);
assert.strictEqual(getFreeze(splitTable).freezeRows,1);
assert.strictEqual(splitTable.context.getCell(0,1).expand,'Down');
assert.strictEqual(undoManager.hasUndo(),false);
applyFreeze(splitTable,{freezeRows:0,freezeColumns:0});
assert.strictEqual(canUnmerge(splitTable,[]),true);
mergeTool.execute();
assert.deepStrictEqual(splitTable.settings.mergeCells,[]);
assert.strictEqual(splitTable.context.getCell(0,1).expand,'Down');
undoManager.undo();
assert.deepStrictEqual(splitTable.settings.mergeCells,[horizontalHeader]);
undoManager.redo();
assert.deepStrictEqual(splitTable.settings.mergeCells,[]);

const verticalSplitTable=new Table();
verticalSplitTable.settings.mergeCells=[Object.assign({},verticalHeader)];
verticalSplitTable.context.getCell(1,0).expand='Down';
applyFreeze(verticalSplitTable,{freezeRows:1,freezeColumns:0});
assert.strictEqual(getFreeze(verticalSplitTable).freezeRows,3);
assert.strictEqual(canUnmerge(verticalSplitTable,[]),false);
assert.strictEqual(canUnmerge(verticalSplitTable,[verticalHeader]),true);
// A normal merged header remains splittable while frozen, including the
// toolbar's restoration of covered cells absent from a loaded template.
const plainSplitTable=new Table();
plainSplitTable.settings.mergeCells=[Object.assign({},horizontalHeader)];
plainSplitTable.context.removeCell(plainSplitTable.context.getCell(0,1));
applyFreeze(plainSplitTable,{freezeRows:1,freezeColumns:0});
assert.strictEqual(canUnmerge(plainSplitTable,[]),true);
plainSplitTable.selected=[0,0,0,1];
new MergeTool(plainSplitTable.context).execute();
assert.deepStrictEqual(plainSplitTable.settings.mergeCells,[]);
assert.strictEqual(getFreeze(plainSplitTable).freezeRows,1);
assert.strictEqual(plainSplitTable.context.getCell(0,1).expand,'None');

// Right expansion has the corresponding split protection in frozen columns.
// Cancelling only the row freeze must not bypass a still-active column freeze.
const columnSplitTable=new Table();
columnSplitTable.settings.mergeCells=[Object.assign({},horizontalHeader),Object.assign({},otherHeader)];
columnSplitTable.context.getCell(0,1).expand='Right';
applyFreeze(columnSplitTable,{freezeRows:1,freezeColumns:1});
assert.deepStrictEqual(getFreeze(columnSplitTable),{freezeRows:1,freezeColumns:2});
assert.strictEqual(canUnmerge(columnSplitTable,[]),false);
assert.strictEqual(canUnmerge(columnSplitTable,[otherHeader]),false);
assert.strictEqual(canUnmerge(columnSplitTable,[horizontalHeader]),true);
const columnMergeTool=new MergeTool(columnSplitTable.context);
columnSplitTable.selected=[0,3,0,4];
columnMergeTool.execute();
assert.deepStrictEqual(columnSplitTable.settings.mergeCells,[horizontalHeader]);
undoManager.clear();
columnSplitTable.selected=[0,1,0,0];
columnMergeTool.execute();
assert.deepStrictEqual(columnSplitTable.settings.mergeCells,[horizontalHeader]);
assert.strictEqual(undoManager.hasUndo(),false);
applyFreeze(columnSplitTable,{freezeRows:0,freezeColumns:2});
assert.strictEqual(canUnmerge(columnSplitTable,[]),false);
applyFreeze(columnSplitTable,{freezeRows:1,freezeColumns:0});
assert.strictEqual(canUnmerge(columnSplitTable,[]),true);
columnMergeTool.execute();
assert.deepStrictEqual(columnSplitTable.settings.mergeCells,[]);
assert.strictEqual(columnSplitTable.context.getCell(0,1).expand,'Right');
assert.deepStrictEqual(getFreeze(columnSplitTable),{freezeRows:1,freezeColumns:0});
undoManager.undo();
assert.deepStrictEqual(columnSplitTable.settings.mergeCells,[horizontalHeader]);
undoManager.redo();
assert.deepStrictEqual(columnSplitTable.settings.mergeCells,[]);

const verticalColumnSplitTable=new Table();
verticalColumnSplitTable.settings.mergeCells=[Object.assign({},verticalHeader)];
verticalColumnSplitTable.context.getCell(1,0).expand='Right';
applyFreeze(verticalColumnSplitTable,{freezeRows:0,freezeColumns:1});
assert.strictEqual(canUnmerge(verticalColumnSplitTable,[]),false);
assert.strictEqual(canUnmerge(verticalColumnSplitTable,[verticalHeader]),true);

// Exercise the toolbar itself to ensure it passes the right selection edge
// to the column guard, including a selection dragged in reverse order.
for(const [selection,allowed] of [
    [[0,0,2,1],true],
    [[3,0,5,1],true],
    [[0,2,2,5],true],
    [[3,2,5,5],true],
    [[4,1,5,2],false],
    [[5,2,4,1],false],
    [[2,0,3,1],false]
]){
    undoManager.clear();
    const mergeTable=new Table();
    applyFreeze(mergeTable,original);
    mergeTable.selected=selection;
    new MergeTool(mergeTable.context).execute();
    assert.strictEqual(mergeTable.settings.mergeCells.length,allowed ? 1 : 0);
    assert.strictEqual(undoManager.hasUndo(),allowed);
    assert.deepStrictEqual(getFreeze(mergeTable),original);
}

undoManager.clear();
const table=new Table();
setFreeze(table,original);
assert.deepStrictEqual(getFreeze(table),original);
const xml=decodeURIComponent(tableToXml(table.context));
assert(xml.includes('<easyreport freeze-rows="3" freeze-columns="2">'));
setFreeze(table,{freezeRows:0,freezeColumns:2});
undoManager.undo();
assert.deepStrictEqual(getFreeze(table),original);
assert.strictEqual(canMerge(table,0,4,2),true);
assert.strictEqual(canMerge(table,2,4,2),true);
assert.strictEqual(canMerge(table,2,5,3),false);
assert.strictEqual(canMerge(table,0,4,3),false);
assert.strictEqual(canMerge(table,5,1,5),true);
assert.strictEqual(canMerge(table,0,1,2),true);
assert.strictEqual(canMerge(table,0,0,2,1),true);
assert.strictEqual(canMerge(table,3,0,5,1),true);
assert.strictEqual(canMerge(table,3,1,5,2),false);
assert.strictEqual(canMerge(table,0,0,2,2),false);
assert.strictEqual(canMerge(table,3,2,5,1),false);
assert.strictEqual(canMerge(table,3,2,5),true);
assert.strictEqual(canExpand(table,'Down',0,4,0,4),false);
assert.strictEqual(canExpand(table,'Right',4,0,4,0),false);
assert.strictEqual(setFreeze(table,{freezeRows:6,freezeColumns:2}),false);
let nativeMerges=0;
table.mergeCells={mergeRange(){nativeMerges++;}};
guardNativeMerge(table);
table.mergeCells.mergeRange({getTopLeftCorner(){return {row:0,col:4};},getBottomRightCorner(){return {row:2,col:5};}});
assert.strictEqual(nativeMerges,1);
table.mergeCells.mergeRange({getTopLeftCorner(){return {row:2,col:4};},getBottomRightCorner(){return {row:3,col:5};}});
assert.strictEqual(nativeMerges,1);
table.mergeCells.mergeRange({getTopLeftCorner(){return {row:4,col:1};},getBottomRightCorner(){return {row:5,col:2};}});
assert.strictEqual(nativeMerges,1);
table.mergeCells.mergeRange({getTopLeftCorner(){return {row:3,col:2};},getBottomRightCorner(){return {row:5,col:3};}});
assert.strictEqual(nativeMerges,2);
table.mergeCells.mergeRange({getTopLeftCorner(){return {row:0,col:0};},getBottomRightCorner(){return {row:2,col:1};}});
assert.strictEqual(nativeMerges,3);
table.mergeCells.mergeRange({getTopLeftCorner(){return {row:4,col:0};},getBottomRightCorner(){return {row:5,col:1};}});
assert.strictEqual(nativeMerges,4);
delete table.mergeCells;
// Loading an old template must clear both axes; an unsafe new file clears only
// its affected axis while retaining an independently valid frozen column band.
applyFreeze(table,{});
assert.deepStrictEqual(getFreeze(table),{freezeRows:0,freezeColumns:0});
table.settings.mergeCells=[{row:0,col:3,rowspan:1,colspan:2}];
applyFreeze(table,original);
assert.deepStrictEqual(getFreeze(table),original);
table.settings.mergeCells=[{row:2,col:3,rowspan:4,colspan:1}];
applyFreeze(table,original);
assert.deepStrictEqual(getFreeze(table),{freezeRows:0,freezeColumns:2});

// HOT 0.32 resets the live merge collection from settings on every settings
// update, while Ctrl+M modifies only the live collection. Freeze changes must
// preserve native merges, including changes made since the preceding freeze.
class NativeMergeTable extends Table {
    updateSettings(settings){
        super.updateSettings(settings);
        this.mergeCells.mergedCellInfoCollection=this.settings.mergeCells.map(merge=>Object.assign({},merge));
    }
}
for(const [expand,requested,expected] of [
    ['Down',{freezeRows:1,freezeColumns:0},{freezeRows:1,freezeColumns:0}],
    ['Right',{freezeRows:0,freezeColumns:1},{freezeRows:0,freezeColumns:2}]
]){
    let nativeUnmerges=0;
    const nativeSplitTable=new NativeMergeTable();
    nativeSplitTable.mergeCells={
        mergedCellInfoCollection:[Object.assign({},horizontalHeader),Object.assign({},otherHeader)],
        mergeRange(){},
        unmergeSelection(coords){
            nativeUnmerges++;
            const index=this.mergedCellInfoCollection.findIndex(merge=>
                coords.row>=merge.row && coords.row<merge.row+merge.rowspan &&
                coords.col>=merge.col && coords.col<merge.col+merge.colspan);
            if(index>=0) this.mergedCellInfoCollection.splice(index,1);
        }
    };
    nativeSplitTable.context.getCell(0,1).expand=expand;
    applyFreeze(nativeSplitTable,requested);
    guardNativeMerge(nativeSplitTable);
    // Ctrl+M can target a covered coordinate; rejection must leave the live
    // collection untouched and never call the original removal implementation.
    nativeSplitTable.mergeCells.unmergeSelection({row:0,col:1});
    assert.strictEqual(nativeUnmerges,0);
    assert.deepStrictEqual(nativeSplitTable.mergeCells.mergedCellInfoCollection,[horizontalHeader,otherHeader]);
    assert.deepStrictEqual(getFreeze(nativeSplitTable),expected);
    nativeSplitTable.mergeCells.unmergeSelection({row:0,col:4});
    assert.strictEqual(nativeUnmerges,1);
    assert.deepStrictEqual(nativeSplitTable.mergeCells.mergedCellInfoCollection,[horizontalHeader]);
    applyFreeze(nativeSplitTable,{freezeRows:0,freezeColumns:0});
    nativeSplitTable.mergeCells.unmergeSelection({row:0,col:1});
    assert.strictEqual(nativeUnmerges,2);
    assert.deepStrictEqual(nativeSplitTable.mergeCells.mergedCellInfoCollection,[]);
}

undoManager.clear();
const nativeTable=new NativeMergeTable();
const nativeMerge={row:3,col:3,rowspan:1,colspan:2};
nativeTable.mergeCells={mergedCellInfoCollection:[nativeMerge]};
// A stale merge inside the frozen area is no longer in the live collection.
nativeTable.settings.mergeCells=[{row:0,col:0,rowspan:1,colspan:2}];
assert.strictEqual(setFreeze(nativeTable,original),true);
assert.deepStrictEqual(getFreeze(nativeTable),original);
assert.deepStrictEqual(nativeTable.mergeCells.mergedCellInfoCollection,[nativeMerge]);
assert.deepStrictEqual(nativeTable.settings.mergeCells,[nativeMerge]);
assert(decodeURIComponent(tableToXml(nativeTable.context)).includes('name="D4" row="4" col="4" col-span="2"'));

const laterMerge={row:4,col:2,rowspan:1,colspan:2};
nativeTable.mergeCells.mergedCellInfoCollection.push(laterMerge);
const expectedMerges=[nativeMerge,laterMerge];
assert.strictEqual(setFreeze(nativeTable,{freezeRows:0,freezeColumns:0}),true);
assert.deepStrictEqual(nativeTable.mergeCells.mergedCellInfoCollection,expectedMerges);
assert.deepStrictEqual(nativeTable.settings.mergeCells,expectedMerges);
undoManager.undo();
assert.deepStrictEqual(getFreeze(nativeTable),original);
assert.deepStrictEqual(nativeTable.mergeCells.mergedCellInfoCollection,expectedMerges);
undoManager.redo();
assert.deepStrictEqual(getFreeze(nativeTable),{freezeRows:0,freezeColumns:0});
assert.deepStrictEqual(nativeTable.mergeCells.mergedCellInfoCollection,expectedMerges);
const nativeXml=decodeURIComponent(tableToXml(nativeTable.context));
assert(nativeXml.includes('name="D4" row="4" col="4" col-span="2"'));
assert(nativeXml.includes('name="C5" row="5" col="3" col-span="2"'));
assert.deepStrictEqual(nativeTable.mergeCells.mergedCellInfoCollection,expectedMerges);
console.log('Freeze rules, save, mutation/undo and editing guards passed.');
