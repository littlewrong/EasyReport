/**
 * Created by Jacky.Gao on 2017-03-22.
 */

export function renderRowHeader(hot,context){
    const countRows=hot.countRows();
    const headers=[];
    const rowHeaders=context.rowHeaders;
    for(let i=1;i<=countRows;i++){
        let type='';
        let pageBreakMark='';
        for(let header of rowHeaders){
            if(header.rowNumber===(i-1)){
                if(header.band==='headerrepeat'){
                    type=`<span style='color:blue;font-size: 10px' title='${window.i18n.table.header.hr}'>HR</span>`;
                }else if(header.band==='footerrepeat'){
                    type=`<span style='color:#d30a16;font-size: 10px' title='${window.i18n.table.header.fr}'>FR</span>`;
                }else if(header.band==='title'){
                    type=`<span style='color:#d30a16;font-size: 10px' title='${window.i18n.table.header.t}'>T</span>`;
                }else if(header.band==='summary'){
                    type=`<span style='color:#d30a16;font-size: 10px' title='${window.i18n.table.header.s}'>S</span>`;
                }
                if(header.pageBreak){
                    pageBreakMark=`<span style='color:#9C27B0;font-size: 11px;font-weight:bold' title='翻页行'>⤓</span>`;
                }
                break;
            }
        }
        headers.push(i+type+pageBreakMark);
    }
    hot.updateSettings({
        rowHeaders:headers
    });
};
