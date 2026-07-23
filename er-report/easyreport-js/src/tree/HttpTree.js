/**
 * HTTP数据源树
 */
import uuid from 'node-uuid';
import {alert,confirm,dialog} from '../MsgBox.js';
import HttpDatasetDialog from '../dialog/HttpDatasetDialog.js';
import BaseTree from './BaseTree.js';

export default class HttpTree extends BaseTree{
    constructor(container,datasources,ds,httpDatasourceDialog,context){
        super();
        this.type='http';
        this.datasources=datasources;
        this.ds=ds;
        this.datasets=ds.datasets || [];
        this.httpDatasourceDialog=httpDatasourceDialog;
        this.context=context;
        this.id=uuid.v1();
        this.name=ds.name;
        this.init(container);
    }

    init(container){
        this.treeContainer=$(`<div class="tree" style="margin-left: 10px"></div>`);
        container.append(this.treeContainer);
        this.ul=$(`<ul style="padding-left: 20px;"></ul>`);
        this.treeContainer.append(this.ul);
        this._buildDatasource();
        for(let dataset of this.datasets){
            const fieldsUL=this.addDataset(dataset);
            this.buildFileds(dataset,fieldsUL);
        }
    }

    _buildDatasource(){
        this.datasourceLi=$(`<li></li>`);
        const rootSpan=$(`
            <span id="${this.id}">
                <i class='easyreport easyreport-minus' style='margin-right:2px'></i>
                <i class="easyreport easyreport-cloud"></i> <a href='###' class="ds_name">${this.name}</a>
            </span>`);
        this.datasourceLi.append(rootSpan);
        this.ul.append(this.datasourceLi);
        this.attachEvent(rootSpan,this.datasourceLi);
        this.datasetUL=$(`<ul style="margin-left: -16px;"></ul>`);
        this.datasourceLi.append(this.datasetUL);
        const _this=this;
        const datasetDialog=new HttpDatasetDialog(this,{parameters:[],fields:[]});

        $.contextMenu({
            selector:'#'+this.id,
            callback:function(key,options){
                if(key==='add'){
                    const span=$(options.selector);
                    datasetDialog.show(function(dataset){
                        _this.datasets.push(dataset);
                        const fieldsUL=_this.addDataset(dataset);
                        _this.buildFileds(dataset,fieldsUL);
                    },{parameters:[],fields:[]});
                }else if(key==='delete'){
                    confirm(`确定要删除HTTP数据源[${_this.name}]吗？`,function(){
                        let index=-1;
                        const datasources=_this.datasources;
                        for(let i=0;i<datasources.length;i++){
                            let d=_this.datasources[i];
                            if(d.name===_this.name){
                                index=i;
                                break;
                            }
                        }
                        datasources.splice(index,1);
                        _this.treeContainer.remove();
                    });
                }else if(key==='edit'){
                    _this.httpDatasourceDialog.show(function(name){
                        _this.name=name;
                        _this.ds.name=name;
                        rootSpan.find(".ds_name").html(name);
                    }, {
                        name: _this.name
                    });
                }
            },
            items:{
                "add": {name:`添加数据集`, icon: "add"},
                "edit": {name: `编辑`, icon: "edit"},
                "delete": {name:`删除`, icon: "delete"}
            }
        });
    }

    addDataset(dataset){
        const li=$(`<li></li>`);
        const spanId=uuid.v1();
        const span=$(`<span id="${spanId}"><i class='easyreport easyreport-minus' style='margin-right:2px'></i> <i class="easyreport easyreport-sqlds"></i> <a href='###' class="dataset_name">${dataset.name}</a></span>`);
        li.append(span);
        this.datasetUL.append(li);
        this.attachEvent(span,li);
        const fieldsUL=$(`<ul style="padding-left: 22px;"></ul>`);
        li.append(fieldsUL);
        const _this=this;
        const datasetDialog=new HttpDatasetDialog(this,dataset);

        const newFiledGroup=$(`<div>输入新字段名称：</div>`);
        const newFieldEditor=$(`<input type="text" class="form-control">`);
        newFiledGroup.append(newFieldEditor);

        $.contextMenu({
            selector:'#'+spanId,
            callback:function(key,options){
                if(key==='add'){
                    const span=$(options.selector);
                    dialog(`添加字段`,newFiledGroup,function(){
                        const newFieldName=newFieldEditor.val();
                        if(!dataset.fields){
                            dataset.fields=[];
                        }
                        for(let field of dataset.fields){
                            if(field.name===newFieldName){
                                alert(`字段已存在`);
                                return;
                            }
                        }
                        let field={name:newFieldName};
                        dataset.fields.push(field);
                        _this.addField(dataset,dataset.fields,field,fieldsUL);
                    });
                }else if(key==='delete'){
                    confirm(`确定要删除数据集[${dataset.name}]吗?`,function(){
                        let index=-1;
                        for(let i=0;i< _this.datasets.length;i++){
                            const d=_this.datasets[i];
                            if(d.name===dataset.name){
                                index=i;
                                break;
                            }
                        }
                        _this.datasets.splice(index,1);
                        li.remove();
                    });
                }else if(key==='edit'){
                    datasetDialog.show(function(newDataset){
                        dataset.name=newDataset.name;
                        dataset.url=newDataset.url;
                        dataset.method=newDataset.method;
                        dataset.requestBody=newDataset.requestBody;
                        dataset.dataPath=newDataset.dataPath;
                        dataset.parameters=newDataset.parameters;
                        dataset.fields=newDataset.fields;
                        span.find('.dataset_name').html(newDataset.name);
                        fieldsUL.empty();
                        _this.buildFileds(dataset,fieldsUL);
                    },dataset);
                }
            },
            items:{
                "add": {name:`添加字段`, icon: "add"},
                "edit": {name: `编辑`, icon: "edit"},
                "delete": {name:`删除`, icon: "delete"}
            }
        });
        return fieldsUL;
    }

    buildFileds(dataset,fieldsUL){
        if(!dataset.fields){
            return;
        }
        for(let field of dataset.fields){
            this.addField(dataset,dataset.fields,field,fieldsUL);
        }
    }

    addField(dataset,fields,field,fieldsUL){
        const _this=this;
        const li=$(`<li></li>`);
        const spanId=uuid.v1();
        const span=$(`<span id="${spanId}" title="双击将字段添加到单元格"><i class="easyreport easyreport-property"></i> <a href='###'>${field.name}</a></span>`);
        li.append(span);
        span.dblclick(function(){
            _this._buildClickEvent(dataset,field,_this.context);
        });
        fieldsUL.append(li);
        $.contextMenu({
            selector:'#'+spanId,
            callback:function(key,options){
                if(key==='delete'){
                    confirm(`确定要删除字段[${field.name}]吗?`,function(){
                        const index=fields.indexOf(field);
                        fields.splice(index,1);
                        li.remove();
                    });
                }
            },
            items:{
                "delete": {name:`删除`, icon: "delete"}
            }
        });
    }

    attachEvent(span,li){
        span.click(function (e) {
            let $liChildren = li.find(' > ul > li');
            if ($liChildren.is(":visible")) {
                $liChildren.hide('fast');
                span.children('i:first').addClass('easyreport-plus').removeClass('easyreport-minus');
            } else {
                $liChildren.show('fast');
                span.children('i:first').addClass('easyreport-minus').removeClass('easyreport-plus');
            }
            e.stopPropagation();
        });
    }
}
