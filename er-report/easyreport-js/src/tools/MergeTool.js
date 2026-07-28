import {undoManager,setDirty,buildNewCellDef} from '../Utils.js';
import {alert} from '../MsgBox.js';
import {cloneMergeCells,getMergeCells} from '../table/MergeCellUtils.js';

/**
 * Created by Jacky.Gao on 2017-01-25.
 */
import Tool from './Tool.js';
export default class MergeTool extends Tool{
    execute(){
        const table=this.context.hot;
        const selected=table.getSelected();
        if(!selected){
            alert(`${window.i18n.selectTargetCellFirst}`);
            return;
        }
        let startRow=selected[0],startCol=selected[1],endRow=selected[2],endCol=selected[3];
        let tmp=endRow;
        if(startRow>endRow){
            endRow=startRow;
            startRow=tmp;
        }
        tmp=endCol;
        if(startCol>endCol){
            endCol=startCol;
            startCol=tmp;
        }
        const oldMergeCells=getMergeCells(table);
        const changed=doMergeCells(startRow,startCol,endRow,endCol,table,this.context);
        if(!changed){
            return;
        }
        const newMergeCells=getMergeCells(table);
        undoManager.add({
            redo:function(){
                table.updateSettings({mergeCells:cloneMergeCells(newMergeCells)});
                setDirty();
            },
            undo:function(){
                table.updateSettings({mergeCells:cloneMergeCells(oldMergeCells)});
                setDirty();
            }
        });
        setDirty();
    }
    getTitle(){
        return `${window.i18n.mergeSplitCells}`;
    }
    getIcon(){
        return `<i class="easyreport easyreport-merge" style="color: #0e90d2;"></i>`;
    }
}
function doMergeCells(startRow,startCol,endRow,endCol,table,context){
    const mergeCells=getMergeCells(table);
    const splitMergeCells=[];
    const remainingMergeCells=[];
    for(let mergeItem of mergeCells){
        if(intersects(mergeItem,startRow,startCol,endRow,endCol)){
            splitMergeCells.push(mergeItem);
        }else{
            remainingMergeCells.push(mergeItem);
        }
    }

    if(splitMergeCells.length>0){
        for(let mergeItem of splitMergeCells){
            const mergeEndRow=mergeItem.row+mergeItem.rowspan-1;
            const mergeEndCol=mergeItem.col+mergeItem.colspan-1;
            for(let row=mergeItem.row;row<=mergeEndRow;row++){
                for(let col=mergeItem.col;col<=mergeEndCol;col++){
                    ensureCellDef(context,row,col);
                }
            }
        }
        table.updateSettings({mergeCells:remainingMergeCells});
        return true;
    }

    if(startRow===endRow && startCol===endCol){
        alert(`${window.i18n.selectMultiTargetCellFirst}`);
        return false;
    }

    ensureCellDef(context,startRow,startCol);
    mergeCells.push({
        row:startRow,
        col:startCol,
        rowspan:endRow-startRow+1,
        colspan:endCol-startCol+1
    });
    table.updateSettings({mergeCells});
    return true;
};

function intersects(mergeItem,startRow,startCol,endRow,endCol){
    const mergeEndRow=mergeItem.row+mergeItem.rowspan-1;
    const mergeEndCol=mergeItem.col+mergeItem.colspan-1;
    return mergeItem.row<=endRow && mergeEndRow>=startRow &&
        mergeItem.col<=endCol && mergeEndCol>=startCol;
};

function ensureCellDef(context,row,col){
    let cellDef=context.getCell(row,col);
    if(!cellDef){
        cellDef=buildNewCellDef(row+1,col+1);
        context.addCell(cellDef);
    }
};
