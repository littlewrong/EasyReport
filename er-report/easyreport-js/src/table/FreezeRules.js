// Pure rules shared by the designer and its boundary tests. Counts are 1-based
// boundaries: freezeRows=3 means rows 1 through 3, never a detached middle row.
export function freezeCount(value){
    const count=Number(value);
    return isFinite(count) && count>0 && Math.floor(count)===count ? count : 0;
}

// Include complete merged areas in either frozen prefix. Extending one merge
// can bring another into the band, so continue until no merged area is cut.
export function expandFreezeRows(count,merges){
    return expandFreezePrefix(count,merges,'row','rowspan');
}

export function expandFreezeColumns(count,merges){
    return expandFreezePrefix(count,merges,'col','colspan');
}

function expandFreezePrefix(count,merges,coordinate,spanName){
    count=freezeCount(count);
    if(!count){
        return 0;
    }
    let extended;
    do{
        extended=false;
        for(let merge of merges || []){
            const start=Number(merge[coordinate]),span=freezeCount(merge[spanName]) || 1;
            const end=start+span;
            if(isFinite(start) && start>=0 && Math.floor(start)===start && start<count && isFinite(end) && end>count){
                count=end;
                extended=true;
            }
        }
    }while(extended);
    return count;
}

export function freezeProblem(axis,count,rowCount,columnCount,merges,cells){
    const rows=axis==='rows';
    count=rows ? expandFreezeRows(count,merges) : expandFreezeColumns(count,merges);
    if(!count){
        return null;
    }
    if(count>=(rows ? rowCount : columnCount)){
        return 'remaining';
    }
    for(let cell of cells || []){
        const expandsInBand=rows ? cell.rowNumber<=count && cell.expand==='Down' : cell.columnNumber<=count && cell.expand==='Right';
        if(expandsInBand && !isCoveredByMerge(cell,merges)){
            return 'expanding';
        }
    }
    return null;
}

function isCoveredByMerge(cell,merges){
    const row=cell.rowNumber-1,col=cell.columnNumber-1;
    for(let merge of merges || []){
        const startRow=Number(merge.row),startCol=Number(merge.col);
        const rowSpan=freezeCount(merge.rowspan) || 1,colSpan=freezeCount(merge.colspan) || 1;
        if(row>=startRow && row<startRow+rowSpan && col>=startCol && col<startCol+colSpan &&
            (row!==startRow || col!==startCol)){
            // Covered definitions remain in the editor until save; only the
            // merged area's anchor is emitted and can expand in the report.
            return true;
        }
    }
    return false;
}

export function normalizeFreeze(state,rowCount,columnCount,merges,cells){
    const result={freezeRows:expandFreezeRows(state.freezeRows,merges),freezeColumns:expandFreezeColumns(state.freezeColumns,merges)};
    if(freezeProblem('rows',result.freezeRows,rowCount,columnCount,merges,cells)){
        result.freezeRows=0;
    }
    if(freezeProblem('columns',result.freezeColumns,rowCount,columnCount,merges,cells)){
        result.freezeColumns=0;
    }
    return result;
}

export function shiftFreeze(state,axis,position,amount,insert){
    const result={freezeRows:freezeCount(state.freezeRows),freezeColumns:freezeCount(state.freezeColumns)};
    const key=axis==='rows' ? 'freezeRows' : 'freezeColumns';
    const count=result[key];
    // An insertion immediately after the frozen band remains scrollable.
    if(insert){
        if(position<count){
            result[key]+=amount;
        }
    }else{
        result[key]-=Math.max(0,Math.min(amount,count-position));
    }
    return result;
}

export function expansionAllowed(state,expand,startRow,startCol,endRow,endCol){
    if(expand==='Down' && Math.min(startRow,endRow)<freezeCount(state.freezeRows)){
        return false;
    }
    if(expand==='Right' && Math.min(startCol,endCol)<freezeCount(state.freezeColumns)){
        return false;
    }
    return true;
}
