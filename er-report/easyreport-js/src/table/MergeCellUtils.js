export function getMergeCells(hot){
    let mergeCells=null;
    const mergePlugin=hot.mergeCells;
    if(mergePlugin && mergePlugin.mergedCellInfoCollection){
        mergeCells=mergePlugin.mergedCellInfoCollection;
    }else{
        const settings=hot.getSettings() || {};
        mergeCells=settings.mergeCells || [];
    }
    return cloneMergeCells(mergeCells);
};

export function cloneMergeCells(mergeCells){
    const result=[];
    if(!Array.isArray(mergeCells)){
        return result;
    }
    for(let item of mergeCells){
        const row=parseInt(item.row,10),col=parseInt(item.col,10);
        const rowspan=Math.max(parseInt(item.rowspan,10) || 1,1);
        const colspan=Math.max(parseInt(item.colspan,10) || 1,1);
        if(isNaN(row) || isNaN(col) || (rowspan===1 && colspan===1)){
            continue;
        }
        const clonedItem={row,col,rowspan,colspan};
        let existingIndex=-1;
        for(let i=0;i<result.length;i++){
            const existing=result[i];
            if(existing.row===row && existing.col===col){
                existingIndex=i;
                break;
            }
        }
        if(existingIndex===-1){
            result.push(clonedItem);
        }else{
            result[existingIndex]=clonedItem;
        }
    }
    return result;
};
