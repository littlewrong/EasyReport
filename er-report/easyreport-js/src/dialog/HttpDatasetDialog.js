/**
 * HTTP数据集对话框
 */
import ParameterTable from './ParameterTable.js';
import {alert} from '../MsgBox.js';
import {setDirty} from '../Utils.js';
import PreviewDataDialog from './PreviewDataDialog.js';

export default class HttpDatasetDialog{
    constructor(httpDs,data){
        this.httpDs=httpDs;
        this.datasources=httpDs.datasources;
        this.data=data;
        this.dialog=$(`<div class="modal fade" role="dialog" aria-hidden="true" style="z-index: 10000;overflow: auto">
            <div class="modal-dialog" style="width: 800px;margin:30px auto;">
                <div class="modal-content">
                    <div class="modal-header">
                        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">
                            &times;
                        </button>
                        <h4 class="modal-title">
                            HTTP数据集配置
                        </h4>
                    </div>
                    <div class="modal-body" style="padding:20px;"></div>
                    <div class="modal-footer"></div>
                </div>
            </div>
        </div>`);
        const body=this.dialog.find('.modal-body'),footer=this.dialog.find(".modal-footer");
        const container=$(`<div></div>`);
        body.append(container);
        this.initEditor(container);
        this.initParameterEditor(container);
        this.initButton(footer);
    }

    initEditor(body){
        const formGroup=$(`<div class="form-horizontal"></div>`);
        body.append(formGroup);

        // 数据集名称
        const nameRow=$(`<div class="form-group" style="margin-bottom: 10px;">
            <label class="col-sm-2 control-label" style="text-align:right;">数据集名称</label>
            <div class="col-sm-10"></div>
        </div>`);
        this.nameEditor=$(`<input type="text" class="form-control" style="font-size: 13px">`);
        nameRow.find('.col-sm-10').append(this.nameEditor);
        formGroup.append(nameRow);

        // HTTP URL
        const urlRow=$(`<div class="form-group" style="margin-bottom: 10px;">
            <label class="col-sm-2 control-label" style="text-align:right;">HTTP地址</label>
            <div class="col-sm-10"></div>
        </div>`);
        this.urlEditor=$(`<input type="text" class="form-control" style="font-size: 13px" placeholder="https://api.example.com/data">`);
        urlRow.find('.col-sm-10').append(this.urlEditor);
        formGroup.append(urlRow);

        // 请求方式
        const methodRow=$(`<div class="form-group" style="margin-bottom: 10px;">
            <label class="col-sm-2 control-label" style="text-align:right;">请求方式</label>
            <div class="col-sm-10"></div>
        </div>`);
        this.methodEditor=$(`<select class="form-control" style="font-size: 13px">
            <option value="POST">POST</option>
            <option value="GET">GET</option>
            <option value="PUT">PUT</option>
            <option value="DELETE">DELETE</option>
        </select>`);
        methodRow.find('.col-sm-10').append(this.methodEditor);
        formGroup.append(methodRow);

        // 协议强制
        const protocolForceRow=$(`<div class="form-group" style="margin-bottom: 10px;">
            <label class="col-sm-2 control-label" style="text-align:right;">协议强制</label>
            <div class="col-sm-10">
                <small class="text-muted">(对相对路径生效，强制使用指定协议)</small>
            </div>
        </div>`);
        this.protocolForceEditor=$(`<select class="form-control" style="font-size: 13px">
            <option value="auto">自动</option>
            <option value="https">HTTPS</option>
            <option value="http">HTTP</option>
        </select>`);
        protocolForceRow.find('.col-sm-10').append(this.protocolForceEditor);
        formGroup.append(protocolForceRow);

        // 请求体
        const requestBodyRow=$(`<div class="form-group" style="margin-bottom: 10px;">
            <label class="col-sm-2 control-label" style="text-align:right;">请求体</label>
            <div class="col-sm-10">
                <small class="text-muted">(JSON格式，使用 \${paramName} 引用参数)</small>
            </div>
        </div>`);
        this.requestBodyEditor=$(`<textarea class="form-control" style="font-size: 13px;height:100px;" placeholder='{"param1":"\${param1}","param2":"\${param2}"}'></textarea>`);
        requestBodyRow.find('.col-sm-10').append(this.requestBodyEditor);
        formGroup.append(requestBodyRow);

        // 数据路径
        const dataPathRow=$(`<div class="form-group" style="margin-bottom: 10px;">
            <label class="col-sm-2 control-label" style="text-align:right;">数据路径</label>
            <div class="col-sm-10">
                <small class="text-muted">(如: data.rows 表示从响应JSON中提取 data.rows 数组)</small>
            </div>
        </div>`);
        this.dataPathEditor=$(`<input type="text" class="form-control" style="font-size: 13px" placeholder="data.rows">`);
        dataPathRow.find('.col-sm-10').append(this.dataPathEditor);
        formGroup.append(dataPathRow);


        // 接口分页配置已移至报表配置
    }

    initParameterEditor(container){
        const formGroup=$(`<div class="form-horizontal"></div>`);
        container.append(formGroup);

        // 参数配置标题行
        const paramTitleRow=$(`<div class="form-group" style="margin-bottom:0px;margin-top:0px;">
            <label class="col-sm-2 control-label" style="text-align:right;">参数配置</label>
            <div class="col-sm-10">
                <small class="text-muted">(参数将在请求体中使用 \${paramName} 引用)</small>
            </div>
        </div>`);
        formGroup.append(paramTitleRow);

        // 参数表格容器
        const paramTableRow=$(`<div class="form-group" style="margin-bottom: 5px;margin-top: 2px;">
            <div class="col-sm-offset-2 col-sm-10"></div>
        </div>`);
        const paramTableContainer=paramTableRow.find('.col-sm-10');
        formGroup.append(paramTableRow);
        this.parameterTable=new ParameterTable(paramTableContainer,this.data.parameters);
    }

    initButton(footer){
        const _this=this;

        // 自动构建字段按钮
        const buildFieldsButton=$(`<button class="btn btn-default">自动构建字段</button>`);
        footer.append(buildFieldsButton);
        buildFieldsButton.click(function(){
            _this.buildFields();
        });

        // 预览按钮
        const previewButton=$(`<button class="btn btn-primary">预览数据</button>`);
        footer.append(previewButton);
        previewButton.click(function(){
            const url=_this.urlEditor.val();
            const method=_this.methodEditor.val();
            const protocolForce=_this.protocolForceEditor.val();
            const requestBody=_this.requestBodyEditor.val();
            const dataPath=_this.dataPathEditor.val();

            if(!url || url===''){
                alert('请输入HTTP地址');
                return;
            }

            const parameters={
                url,
                method,
                protocolForce,
                requestBody,
                dataPath,
                parameters:JSON.stringify(_this.data.parameters)
            };

            const previewDialog=new PreviewDataDialog();
            previewDialog.show();
            const apiUrl=window._server+"/datasource/previewHttpData";
            $.ajax({
                type:'POST',
                url:apiUrl,
                data:parameters,
                success:function(data){
                    previewDialog.showData(data);
                },
                error:function(response){
                    if(response && response.responseText){
                        alert("服务端错误："+response.responseText+"");
                    }else{
                        previewDialog.showError(`<div style='color: #d30e00;'>数据预览失败</div>`);
                    }
                }
            });
        });

        // 确认按钮
        const confirmButton=$(`<button class="btn btn-primary">确定</button>`);
        footer.append(confirmButton);
        confirmButton.click(function(){
            const name=_this.nameEditor.val();
            const url=_this.urlEditor.val();
            const method=_this.methodEditor.val();
            const protocolForce=_this.protocolForceEditor.val();
            const requestBody=_this.requestBodyEditor.val();
            const dataPath=_this.dataPathEditor.val();

            if(!name || name===""){
                alert(`请输入数据集名称`);
                return;
            }

            if(!url || url===""){
                alert(`请输入HTTP地址`);
                return;
            }

            let check=false;
            if(!_this.oldName || name!==_this.oldName){
                check=true;
            }
            if(check){
                for(let datasource of _this.datasources){
                    let datasets=datasource.datasets;
                    if(!datasets)continue;
                    for(let dataset of datasets){
                        if(dataset.name===name){
                            alert(`数据集[${name}]已经存在`);
                            return;
                        }
                    }
                }
            }

            const fields=_this.data.fields;
            _this.onSave.call(_this,{
                name,
                url,
                method,
                protocolForce,
                requestBody,
                fields,
                dataPath,
                parameters:_this.data.parameters
            });
            setDirty();
            _this.dialog.modal('hide');
        });
    }

    buildFields(){
        const _this=this;
        const url=this.urlEditor.val();
        const method=this.methodEditor.val();
        const protocolForce=this.protocolForceEditor.val();
        const requestBody=this.requestBodyEditor.val();
        const dataPath=this.dataPathEditor.val();

        if(!url || url===''){
            alert('请输入HTTP地址');
            return;
        }

        const parameters={
            url,
            method,
            protocolForce,
            requestBody,
            dataPath,
            parameters:JSON.stringify(this.data.parameters)
        };

        const apiUrl=window._server+"/datasource/buildHttpFields";
        $.ajax({
            type:'POST',
            url:apiUrl,
            data:parameters,
            success:function(data){
                if(data && data.length>0){
                    _this.data.fields=data;
                    alert(`成功构建${data.length}个字段`);
                }else{
                    alert(`未能获取到字段信息，请检查数据路径配置`);
                }
            },
            error:function(response){
                if(response && response.responseText){
                    alert("服务端错误："+response.responseText+"");
                }else{
                    alert(`字段构建失败`);
                }
            }
        });
    }

    show(onSave,ds){
        this.onSave=onSave;
        if(ds){
            this.oldName=ds.name;
            this.nameEditor.val(ds.name);
            this.urlEditor.val(ds.url || '');
            this.methodEditor.val(ds.method || 'POST');
            this.protocolForceEditor.val(ds.protocolForce || 'https');
            this.requestBodyEditor.val(ds.requestBody || '');
            this.dataPathEditor.val(ds.dataPath || '');
        }else{
            this.oldName=null;
            this.nameEditor.val('');
            this.urlEditor.val('');
            this.methodEditor.val('POST');
            this.protocolForceEditor.val('https');
            this.requestBodyEditor.val('');
            this.dataPathEditor.val('');
        }
        this.dialog.modal('show');
    }
}
