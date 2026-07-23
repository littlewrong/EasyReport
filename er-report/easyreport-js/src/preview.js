/**
 * Created by Jacky.Gao on 2017-03-17.
 */
import './form/external/bootstrap-datetimepicker.css';
import {pointToMM,showLoading,hideLoading} from './Utils.js';
import {alert} from './MsgBox.js';
import PDFPrintDialog from './dialog/PDFPrintDialog.js';
// 使用轮询方案（更稳定，适合Istio多节点环境）
// 如需切换回SSE，改为: import StreamingExportProgress from './export-progress.js';
import StreamingExportProgress from './export-progress-polling.js';
import defaultI18nJsonData from './i18n/preview.json';
import en18nJsonData from './i18n/preview_en.json';
(function($){
    $.fn.datetimepicker.dates['zh-CN'] = {
        days: ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"],
        daysShort: ["周日", "周一", "周二", "周三", "周四", "周五", "周六", "周日"],
        daysMin:  ["日", "一", "二", "三", "四", "五", "六", "日"],
        months: ["一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月"],
        monthsShort: ["一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月"],
        today: "今天",
        suffix: [],
        meridiem: ["上午", "下午"]
    };
}(jQuery));

$(document).ready(function(){
    let language=window.navigator.language || window.navigator.browserLanguage;
    if(!language){
        language='zh-cn';
    }
    language=language.toLowerCase();
    window.i18n=defaultI18nJsonData;
    if(language!=='zh-cn'){
        window.i18n=en18nJsonData;
    }
    $('.easyreport-print').click(function(){
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/preview/loadPrintPages'+urlParameters;
        showLoading();
        $.ajax({
            url,
            type:'POST',
            success:function(result){
                $.get(window._server+'/preview/loadPagePaper'+urlParameters,function(paper){
                    hideLoading();
                    const html=result.html;
                    const iFrame=window.frames['_print_frame'];
                    let styles=`<style type="text/css">`;
                    styles+=buildPrintStyle(paper);
                    styles+=$('#_easyreport_table_style').html();
                    styles+=`</style>`;
                    $(iFrame.document.body).html(styles+html);
                    waitPrintImages(iFrame.document,3000).then(function(){
                        iFrame.window.focus();
                        iFrame.window.print();
                    });
                });
            },
            error:function(response){
                hideLoading();
                if(response && response.responseText){
                    alert("服务端错误："+response.responseText+"");
                }else{
                    alert("服务端出错！");
                }
            }
        });
    });
    let directPrintPdf=false,index=0;
    const pdfPrintDialog=new PDFPrintDialog();
    $(`.easyreport-pdf-print`).click(function(){
        const urlParameters=buildLocationSearchParameters();
        $.get(window._server+'/preview/loadPagePaper'+urlParameters,function(paper){
            pdfPrintDialog.show(paper);
        });
    });
    $(`.easyreport-pdf-direct-print`).click(function(){
        showLoading();
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/pdf/show'+urlParameters+`&_i=${index++}`;
        const iframe=window.frames['_print_pdf_frame'];
        if(!directPrintPdf){
            directPrintPdf=true;
            $("iframe[name='_print_pdf_frame']").on("load",function(){
                hideLoading();
                iframe.window.focus();
                iframe.window.print();
            });
        }
        iframe.window.focus();
        iframe.location.href=url;
    });
    $(`.easyreport-export-pdf`).click(function(){
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/pdf'+urlParameters;
        window.open(url,'_blank');
    });
    $(`.easyreport-export-word`).click(function(){
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/word'+urlParameters;
        window.open(url,'_blank');
    });
    $(`.easyreport-export-excel`).click(function(){
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/excel'+urlParameters;
        window.open(url,'_blank');
    });
    // 数据导出：弹窗输入页码与条数，默认 page=1，page_size=100000，遵循HTTP数据源配置
    function buildApiExportModal(){
        if($('#excelApiExportModal').length>0){
            return $('#excelApiExportModal');
        }
        const modal=$(`
        <div class="modal fade" id="excelApiExportModal" tabindex="-1" role="dialog" aria-hidden="true" style="z-index:10600;">
          <div class="modal-dialog" style="width:520px;">
            <div class="modal-content">
              <div class="modal-header">
                <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                <h4 class="modal-title">数据导出</h4>
              </div>
              <div class="modal-body">
                <div class="form-group">
                  <label>导出条数</label>
                  <input type="number" min="1" class="form-control" id="excelApiPageSizeVal">
                  <p class="help-block" id="excelApiParamHint" style="margin-top:4px;"></p>
                </div>
              </div>
              <div class="modal-footer">
                <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
                <button type="button" class="btn btn-primary" id="excelApiExportConfirm">导出</button>
              </div>
            </div>
          </div>
        </div>`);
        $('body').append(modal);
        return modal;
    }
    const apiModal=buildApiExportModal();
    const sizeValInput=apiModal.find('#excelApiPageSizeVal');
    const paramHint=apiModal.find('#excelApiParamHint');
    let currentApiConfig=null;
    const apiButton=$('.easyreport-export-excel-api');

    function normalizeApiConfig(config){
        if(!config){
            return {
                pageParamName:'page',
                pageSizeParamName:'page_size',
                defaultPageSize:100000,
                maxPageSize:100000,
                pageSize:100,
                apiPagingEnabled:true,
                apiStartFieldName:undefined,
                apiEndFieldName:undefined
            };
        }
        return {
            pageParamName:config.pageParamName || 'page',
            pageSizeParamName:config.pageSizeParamName || 'page_size',
            defaultPageSize:config.defaultPageSize || 100000,
            maxPageSize:config.maxPageSize || 100000,
            pageSize:config.pageSize || 100,
            apiPagingEnabled:config.apiPagingEnabled!==false,
            apiStartFieldName:config.apiStartFieldName,
            apiEndFieldName:config.apiEndFieldName
        };
    }

    function pickApiConfig(){
        const cfg=window._apiPagingConfig;
        if(cfg){
            return normalizeApiConfig(cfg);
        }
        return normalizeApiConfig(null);
    }

    function hasApiPagingEnabled(){
        const cfg=window._apiPagingConfig;
        if(cfg){
            return !!cfg.apiPagingEnabled;
        }
        return false;
    }

    function initApiPagingInfo(){
        const cfg=window._apiPagingConfig;
        if(!cfg || !cfg.apiPagingEnabled){
            $('#apiPagingInfo').hide();
            return;
        }

        const totalCount = cfg.totalCount || 0;
        const pageSize = cfg.pageSize || 100;

        if(totalCount > 0){
            const totalPages = Math.ceil(totalCount / pageSize);
            $('#apiTotalCount').text(totalCount);
            $('#apiTotalPages').text(totalPages);

            // 设置页码输入框
            const pageSelector = $('#apiPageSelector');

            // 获取当前URL中的page参数
            const urlParams = new URLSearchParams(window.location.search);
            const pageParamName = cfg.pageParamName || 'page';
            const currentPage = parseInt(urlParams.get(pageParamName)) || 1;

            // 设置输入框属性
            pageSelector.attr('max', totalPages);
            pageSelector.val(currentPage);

            // 添加回车键跳转事件监听器
            pageSelector.off('keypress').on('keypress', function(e){
                if(e.which === 13 || e.keyCode === 13){
                    const inputPage = parseInt($(this).val());
                    const pageParamName = cfg.pageParamName || 'page';
                    const pageSizeParamName = cfg.pageSizeParamName || 'page_size';

                    if(isNaN(inputPage) || inputPage < 1){
                        alert('请输入有效的页码（大于0）');
                        $(this).val(currentPage);
                        return;
                    }

                    if(inputPage > totalPages){
                        alert(`页码不能超过最大页数 ${totalPages}`);
                        $(this).val(currentPage);
                        return;
                    }

                    // 获取当前URL
                    const currentUrl = window.location.href;
                    const urlObj = new URL(currentUrl);
                    const params = new URLSearchParams(urlObj.search);

                    // 设置或更新分页参数
                    params.set(pageParamName, inputPage);
                    params.set(pageSizeParamName, pageSize);

                    // 重新加载页面
                    window.location.href = urlObj.pathname + '?' + params.toString();
                }
            });

            $('#apiPagingInfo').show();
        }else{
            $('#apiPagingInfo').hide();
        }
    }

    if(!hasApiPagingEnabled()){
        apiButton.hide();
        $('#apiPagingInfo').hide();
    }else{
        apiButton.show();
        // 初始化API分页信息显示
        initApiPagingInfo();
    }

    $(`.easyreport-export-excel-api`).click(function(){
        const cfg=pickApiConfig();
        currentApiConfig=cfg;
        // 使用配置的最大条数作为默认值
        sizeValInput.val(cfg.maxPageSize || 100000);
        paramHint.text(cfg.maxPageSize ? `（最大${cfg.maxPageSize}条）` : '');
        apiModal.modal('show');
    });
    apiModal.find('#excelApiExportConfirm').off('click').on('click',function(){
        const cfg=currentApiConfig || pickApiConfig();
        if(!cfg.apiPagingEnabled){
            alert('当前HTTP数据集未开启数据导出');
            return;
        }
        const sizeVal=sizeValInput.val().trim();
        if(!sizeVal){
            alert('导出条数不能为空');
            return;
        }
        const pageNum=1;  // 固定为第1页，避免offset性能问题
        const sizeNum=parseInt(sizeVal,10);
        if(isNaN(sizeNum)||sizeNum<=0){
            alert('导出条数必须为大于0的数字');
            return;
        }
        let finalSize=sizeNum;
        if(cfg.maxPageSize && sizeNum>cfg.maxPageSize){
            finalSize=cfg.maxPageSize;
            alert(`分页大小不能超过${cfg.maxPageSize}，已自动使用最大值`);
        }
        // 使用流式导出
        const params=buildParameterMap([cfg.pageParamName,cfg.pageSizeParamName]);
        const queryParts=[];
        for(let key in params){
            queryParts.push(`${key}=${params[key]}`);
        }
        queryParts.push(`${encodeURIComponent(cfg.pageParamName)}=${encodeURIComponent(pageNum)}`);
        queryParts.push(`${encodeURIComponent(cfg.pageSizeParamName)}=${encodeURIComponent(finalSize)}`);

        // 提取日期参数并传递给后端（用于按日期循环导出）
        const startFieldName=cfg.apiStartFieldName;
        const endFieldName=cfg.apiEndFieldName;

        // 详细调试日志
        console.log('[按日期循环] cfg对象:', cfg);
        console.log('[按日期循环] cfg.apiStartFieldName:', cfg.apiStartFieldName);
        console.log('[按日期循环] cfg.apiEndFieldName:', cfg.apiEndFieldName);
        console.log('[按日期循环] 计算后的字段名:', {startFieldName, endFieldName});
        console.log('[按日期循环] params对象:', params);
        console.log('[按日期循环] params的所有键:', Object.keys(params));

        const startDate=params[startFieldName];
        const endDate=params[endFieldName];

        console.log('[按日期循环] 提取的日期值:', {startDate, endDate});

        if(startDate && startFieldName){
            queryParts.push(`_startDate=${encodeURIComponent(startDate)}`);
            queryParts.push(`_startFieldName=${encodeURIComponent(startFieldName)}`);
        }
        if(endDate && endFieldName){
            queryParts.push(`_endDate=${encodeURIComponent(endDate)}`);
            queryParts.push(`_endFieldName=${encodeURIComponent(endFieldName)}`);
        }

        // 流式导出：调用 streamingExport 方法
        const url=window._server+'/excel/streamingExport?'+queryParts.join('&');

        // 使用SSE进度显示
        apiModal.modal('hide');
        const progressHandler = StreamingExportProgress();
        progressHandler.start(url);
    });
    $(`.easyreport-export-excel-paging`).click(function(){
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/excel/paging'+urlParameters;
        window.open(url,'_blank');
    });
    $(`.easyreport-export-excel-paging-sheet`).click(function(){
        const urlParameters=buildLocationSearchParameters();
        const url=window._server+'/excel/sheet'+urlParameters;
        window.open(url,'_blank');
    });
});

window._currentPageIndex=null;
window._totalPage=null;

window.buildLocationSearchParameters=function(exclude){
    let urlParameters=window.location.search;
    if(urlParameters.length>0){
        urlParameters=urlParameters.substring(1,urlParameters.length);
    }
    let parameters={};
    const pairs=urlParameters.split('&');
    for(let i=0;i<pairs.length;i++){
        const item=pairs[i];
        if(item===''){
            continue;
        }
        const param=item.split('=');
        let key=param[0];
        if(exclude && key===exclude){
            continue;
        }
        let value=param[1];
        parameters[key]=value;
    }
    if(window.searchFormParameters){
        for(let key in window.searchFormParameters){
            if(key===exclude){
                continue;
            }
            const value=window.searchFormParameters[key];
            if(value){
                parameters[key]=value;
            }
        }
    }
    let p='?';
    for(let key in parameters){
        if(p==='?'){
            p+=key+'='+parameters[key];
        }else{
            p+='&'+key+'='+parameters[key];
        }
    }
    return p;
};

function buildParameterMap(exclude){
    const excludes=Array.isArray(exclude)?exclude.filter(e=>!!e): (exclude?[exclude]:[]);
    let urlParameters=window.location.search;
    if(urlParameters.length>0){
        urlParameters=urlParameters.substring(1,urlParameters.length);
    }
    let parameters={};
    const pairs=urlParameters.split('&');
    for(let i=0;i<pairs.length;i++){
        const item=pairs[i];
        if(item===''){
            continue;
        }
        const param=item.split('=');
        let key=param[0];
        if(excludes.indexOf(key)>-1){
            continue;
        }
        let value=param[1];
        parameters[key]=value;
    }
    if(window.searchFormParameters){
        for(let key in window.searchFormParameters){
            if(excludes.indexOf(key)>-1){
                continue;
            }
            const value=window.searchFormParameters[key];
            if(value){
                parameters[key]=value;
            }
        }
    }
    return parameters;
}

function waitPrintImages(doc,timeout){
    const images=Array.from(doc.images || []);
    if(images.length===0){
        return Promise.resolve();
    }
    const waitAll=Promise.all(images.map(function(img){
        if(img.complete){
            if(img.decode && img.naturalWidth>0){
                return img.decode().catch(function(){});
            }
            return Promise.resolve();
        }
        return new Promise(function(resolve){
            img.onload=function(){
                if(img.decode && img.naturalWidth>0){
                    img.decode().catch(function(){}).then(resolve);
                }else{
                    resolve();
                }
            };
            img.onerror=resolve;
        });
    }));
    const waitTimeout=new Promise(function(resolve){
        setTimeout(resolve,timeout || 3000);
    });
    return Promise.race([waitAll,waitTimeout]);
}

function buildPrintStyle(paper){
    const marginLeft=pointToMM(paper.leftMargin);
    const marginTop=pointToMM(paper.topMargin);
    const marginRight=pointToMM(paper.rightMargin);
    const marginBottom=pointToMM(paper.bottomMargin);
    const paperType=paper.paperType;
    let page=paperType;
    if(paperType==='CUSTOM'){
        page=pointToMM(paper.width)+'mm '+pointToMM(paper.height)+'mm';
    }
    const style=`
        @media print {
            .page-break{
                display: block;
                page-break-before: always;
            }
        }
        @page {
          size: ${page} ${paper.orientation};
          margin-left: ${marginLeft}mm;
          margin-top: ${marginTop}mm;
          margin-right:${marginRight}mm;
          margin-bottom:${marginBottom}mm;
        }
    `;
    return style;
};

window.buildPaging=function(pageIndex,totalPage){
    if(totalPage===0){
        return;
    }
    if(!pageIndex){
        return;
    }
    if(!window._currentPageIndex){
        window._currentPageIndex=pageIndex;
    }
    pageIndex=window._currentPageIndex;
    if(!window._totalPage){
        window._totalPage=totalPage;
    }

    const pageSelector=$('#pageSelector');
    pageSelector.change(function(){
        const parameters=window.buildLocationSearchParameters('_i');
        let url=window._server+`/preview${parameters}&_i=${$(this).val()}`;
        window.open(url,'_self');
    });
    pageSelector.val(pageIndex);
    if(totalPage===1){
        return;
    }
    const parameters=window.buildLocationSearchParameters('_i');
    const pagingContainer=$('#pageLinkContainer');
    pagingContainer.empty();
    if(pageIndex>1){
        let url=window._server+`/preview${parameters}&_i=${pageIndex-1}`;
        const prevPage=$(`<button type="button" class="btn btn-link btn-sm">上一页</button>`);
        pagingContainer.append(prevPage);
        prevPage.click(function(){
            window.open(url,'_self');
        });
    }
    if(pageIndex<totalPage){
        let url=window._server+`/preview${parameters}&_i=${pageIndex+1}`;
        const nextPage=$(`<button type="button" class="btn btn-link btn-sm">下一页</button>`);
        pagingContainer.append(nextPage);
        nextPage.click(function(){
            window.open(url,'_self');
        });
    }
};

window._intervalRefresh=function(value,totalPage){
    if(!value){
        return;
    }
    window._totalPage=totalPage;
    const second=value*1000;
    setTimeout(function(){
        _refreshData(second);
    },second);
};

function _refreshData(second){
    const params=buildLocationSearchParameters('_i');
    let url=window._server+`/preview/loadData${params}`;
    const totalPage=window._totalPage;
    if(totalPage>0){
        if(window._currentPageIndex){
            if(window._currentPageIndex>totalPage){
                window._currentPageIndex=1;
            }
            url+="&_i="+window._currentPageIndex+"";
        }
        $("#pageSelector").val(window._currentPageIndex);
    }
    $.ajax({
        url,
        type:'GET',
        success:function(report){
            const tableContainer=$(`#_easyreport_table`);
            tableContainer.empty();
            window._totalPage=report.totalPageWithCol;
            tableContainer.append(report.content);
            _buildChartDatas(report.chartDatas);
            buildPaging(window._currentPageIndex,window._totalPage);
            if(window._currentPageIndex){
                window._currentPageIndex++;
            }
            setTimeout(function(){
                _refreshData(second);
            },second);
        },
        error:function(response){
            const tableContainer=$(`#_easyreport_table`);
            tableContainer.empty();
            if(response && response.responseText){
                tableContainer.append("<h3 style='color: #d30e00;'>服务端错误："+response.responseText+"</h3>");
            }else{
                tableContainer.append("<h3 style='color: #d30e00;'>加载数据失败</h3>");
            }
            setTimeout(function(){
                _refreshData(second);
            },second);
        }
    });
};

window._buildChartDatas=function(chartData){
    if(!chartData){
        return;
    }
    for(let d of chartData){
        let json=d.json;
        json=JSON.parse(json,function (k, v) {
            if(v.indexOf && v.indexOf('function') > -1){
                return eval("(function(){return "+v+" })()")
            }
            return v;
        });
        _buildChart(d.id,json);
    }
};
window._buildChart=function(canvasId,chartJson){
    const ctx=document.getElementById(canvasId);
    if(!ctx){
        return;
    }
    let options=chartJson.options;
    if(!options){
        options={};
        chartJson.options=options;
    }
    let animation=options.animation;
    if(!animation){
        animation={};
        options.animation=animation;
    }
    animation.onComplete=function(event){
        const chart=event.chart;
        const base64Image=chart.toBase64Image();
        const urlParameters=window.location.search;
        const url=window._server+'/chart/storeData'+urlParameters;
        const canvas=$("#"+canvasId);
        const width=parseInt(canvas.css('width'));
        const height=parseInt(canvas.css('height'));
        $.ajax({
            type:'POST',
            data:{_base64Data:base64Image,_chartId:canvasId,_width:width,_height:height},
            url
        });
    };
    const chart=new Chart(ctx,chartJson);
};

window.submitSearchForm=function(file,customParameters){
    window.searchFormParameters={};
    for(let fun of window.formElements){
        const json=fun.call(this);
        for(let key in json){
            let value=json[key];
            value=encodeURI(value);
            value=encodeURI(value);
            window.searchFormParameters[key]=value;
        }
    }
    const parameters=window.buildLocationSearchParameters('_i');
    let url=window._server+"/preview/loadData"+parameters;
    const pageSelector=$(`#pageSelector`);
    if(pageSelector.length>0){
        url+='&_i=1';
    }
    $.ajax({
        url,
        type:'POST',
        success:function(report){
            window._currentPageIndex=1;
            const tableContainer=$(`#_easyreport_table`);
            tableContainer.empty();
            tableContainer.append(report.content);
            _buildChartDatas(report.chartDatas);
            const totalPage=report.totalPage;
            window._totalPage=totalPage;
            if(pageSelector.length>0){
                pageSelector.empty();
                for(let i=1;i<=totalPage;i++){
                    pageSelector.append(`<option>${i}</option>`);
                }
                const pageIndex=report.pageIndex || 1;
                pageSelector.val(pageIndex);
                $('#totalPageLabel').html(totalPage);
                buildPaging(pageIndex,totalPage);
            }
        },
        error:function(response){
            if(response && response.responseText){
                alert("服务端错误："+response.responseText+"");
            }else{
                alert('查询操作失败！');
            }
        }
    });
};
