import {undoManager,setDirty} from '../Utils.js';
import {alert} from '../MsgBox.js';
import {getMergeCells} from './MergeCellUtils.js';
import {freezeCount,freezeProblem,normalizeFreeze,expansionAllowed,expandFreezeRows,expandFreezeColumns} from './FreezeRules.js';

export function freezeText(key){
    return window.i18n.table.freeze[key];
}

export function getFreeze(hot){
    const settings=hot.getSettings();
    return {freezeRows:freezeCount(settings.fixedRowsTop),freezeColumns:freezeCount(settings.fixedColumnsLeft)};
}

function getCells(hot){
    return hot.context ? Array.from(hot.context.cellsMap.values()) : [];
}

export function applyFreeze(hot,state,reportDef,cells){
    const mergeCells=getMergeCells(hot);
    const safe=normalizeFreeze(state,hot.countRows(),hot.countCols(),mergeCells,cells || getCells(hot));
    const target=reportDef || (hot.context && hot.context.reportDef);
    if(target){
        target.freezeRows=safe.freezeRows;
        target.freezeColumns=safe.freezeColumns;
    }
    hot.rootElement.classList.toggle('easyreport-freeze-rows',safe.freezeRows>0);
    hot.rootElement.classList.toggle('easyreport-freeze-columns',safe.freezeColumns>0);
    // HOT 0.32 rebuilds the merge plugin on every updateSettings call. Native
    // Ctrl+M changes its live collection only, so preserve that current snapshot.
    hot.updateSettings({fixedRowsTop:safe.freezeRows,fixedColumnsLeft:safe.freezeColumns,mergeCells});
    return safe;
}

export function setFreeze(hot,state){
    const cells=getCells(hot),merges=getMergeCells(hot);
    const candidate={freezeRows:expandFreezeRows(state.freezeRows,merges),freezeColumns:expandFreezeColumns(state.freezeColumns,merges)};
    for(let axis of ['rows','columns']){
        const count=axis==='rows' ? candidate.freezeRows : candidate.freezeColumns;
        const issue=freezeProblem(axis,count,hot.countRows(),hot.countCols(),merges,cells);
        if(issue){
            alert(freezeText(issue+(axis==='rows' ? 'Rows' : 'Columns')));
            return false;
        }
    }
    const previous=getFreeze(hot);
    if(previous.freezeRows===candidate.freezeRows && previous.freezeColumns===candidate.freezeColumns){
        return true;
    }
    const next=applyFreeze(hot,candidate);
    undoManager.add({
        undo:function(){ applyFreeze(hot,previous);setDirty(); },
        redo:function(){ applyFreeze(hot,next);setDirty(); }
    });
    setDirty();
    return true;
}

export function freezeSelection(hot,axis){
    const selected=hot.getSelected();
    if(!selected){
        return;
    }
    const state=getFreeze(hot);
    if(axis==='rows'){
        state.freezeRows=Math.max(selected[0],selected[2])+1;
    }else{
        state.freezeColumns=Math.max(selected[1],selected[3])+1;
    }
    setFreeze(hot,state);
}

export function canExpand(hot,expand,startRow,startCol,endRow,endCol){
    if(!expansionAllowed(getFreeze(hot),expand,startRow,startCol,endRow,endCol)){
        alert(freezeText(expand==='Down' ? 'expandRows' : 'expandColumns'));
        return false;
    }
    return true;
}

export function canMerge(hot,startRow,startCol,endRow=startRow,endCol=startCol){
    const state=getFreeze(hot);
    if(Math.min(startCol,endCol)<state.freezeColumns && Math.max(startCol,endCol)>=state.freezeColumns){
        alert(freezeText('mergeColumnsBoundary'));
        return false;
    }
    if(Math.min(startRow,endRow)<state.freezeRows && Math.max(startRow,endRow)>=state.freezeRows){
        alert(freezeText('mergeRowsBoundary'));
        return false;
    }
    return true;
}

export function canUnmerge(hot,remainingMerges){
    const state=getFreeze(hot),cells=getCells(hot);
    for(let axis of ['rows','columns']){
        const count=axis==='rows' ? state.freezeRows : state.freezeColumns;
        const issue=freezeProblem(axis,count,hot.countRows(),hot.countCols(),remainingMerges,cells);
        if(issue==='expanding'){
            alert(freezeText(axis==='rows' ? 'unmergeExpandingRows' : 'unmergeExpandingColumns'));
            return false;
        }
    }
    return true;
}

// The old Handsontable also exposes Ctrl+M. Guard its merge entry so it cannot
// bypass the designer toolbar's validation. Unmerging may expose source cells
// whose expansion was inactive while covered by a merged area.
export function guardNativeMerge(hot){
    const plugin=hot.mergeCells;
    if(!plugin || plugin._easyreportFreezeGuard){
        return;
    }
    const mergeRange=plugin.mergeRange;
    plugin.mergeRange=function(range){
        const start=range.getTopLeftCorner();
        const end=range.getBottomRightCorner();
        if(canMerge(hot,start.row,start.col,end.row,end.col)){
            return mergeRange.call(this,range);
        }
    };
    if(typeof plugin.unmergeSelection==='function'){
        const unmergeSelection=plugin.unmergeSelection;
        plugin.unmergeSelection=function(coords){
            const remainingMerges=getMergeCells(hot).filter(merge=>
                !(coords.row>=merge.row && coords.row<merge.row+merge.rowspan &&
                  coords.col>=merge.col && coords.col<merge.col+merge.colspan));
            if(canUnmerge(hot,remainingMerges)){
                return unmergeSelection.call(this,coords);
            }
        };
    }
    plugin._easyreportFreezeGuard=true;
}
