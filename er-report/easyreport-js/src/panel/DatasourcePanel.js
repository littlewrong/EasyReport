/**
 * Created by Jacky.Gao on 2017-02-04.
 */
import DatabaseTree from '../tree/DatabaseTree.js';
import SpringTree from '../tree/SpringTree.js';
import BuildinTree from '../tree/BuildinTree.js';
import HttpTree from '../tree/HttpTree.js';
import DatasourceDialog from '../dialog/DatasourceDialog.js';
import SpringDialog from '../dialog/SpringDialog.js';
import HttpDatasourceDialog from '../dialog/HttpDatasourceDialog.js';
import BuildinDatasourceSelectDialog from '../dialog/BuildinDatasourceSelectDialog.js';

export default class DatasourcePanel{
    constructor(context){
        this.context=context;
        context.datasourcePanel=this;
        const reportDef=context.reportDef;
        if(!reportDef.datasources){
            reportDef.datasources=[];
        }
        this.datasources=reportDef.datasources;
    }
    buildPanel(){
        const panel=$(`<div style="width:100%;"></div>`);
        const toolbar=$(`<div class="btn-group ud-toolbar"></div>`);
        panel.append(toolbar);
        const addSqlBtn=$(`<button class="btn btn-default" style="border:none;border-radius:0;background: #f8f8f8;padding: 6px 8px;" title="${window.i18n.property.datasource.title}">
            <i class="easyreport easyreport-database"></i>
        </button>`);
        toolbar.append(addSqlBtn);

        this.treeContainer=$(`<div style="height: 500px;overflow: auto">`);
        panel.append(this.treeContainer);

        this.datasourceDialog=new DatasourceDialog(this.datasources);
        const _this=this;

        addSqlBtn.click(function(){
            _this.datasourceDialog.show(function(name,username,password,driver,url){
                const ds={name,username,password,driver,url};
                const tree=new DatabaseTree(_this.treeContainer,_this.datasources,ds,_this.datasourceDialog,_this.context);
                _this.datasources.push(tree);
            });
        });
        const addSpringBtn=$(`<button class="btn btn-default" style="border:none;border-radius:0;background: #f8f8f8;padding: 6px 8px;" title="${window.i18n.property.datasource.addBean}">
            <i class="easyreport easyreport-leaf"></i>
        </button>`);
        toolbar.append(addSpringBtn);
        this.springDialog=new SpringDialog(this.datasources);
        addSpringBtn.click(function(){
            _this.springDialog.show(function(name,beanId){
                const ds={name,beanId};
                const tree=new SpringTree(_this.treeContainer,_this.datasources,ds,_this.springDialog,_this.context);
                _this.datasources.push(tree);
            });
        });
        const addBuildinBtn=$(`<button class="btn btn-default" style="border:none;border-radius:0;background: #f8f8f8;padding: 6px 8px;" title="${window.i18n.property.datasource.addBuildin}">
            <i class="easyreport easyreport-shareconnection"></i>
        </button>`);
        toolbar.append(addBuildinBtn);
        const buildinDatasourceSelectDialog=new BuildinDatasourceSelectDialog(this.datasources);
        addBuildinBtn.click(function(){
            buildinDatasourceSelectDialog.show(function(name){
                const ds={name};
                const tree=new BuildinTree(_this.treeContainer,_this.datasources,ds,_this.context);
                _this.datasources.push(tree);
            });
        });

        // HTTP数据源按钮
        const addHttpBtn=$(`<button class="btn btn-default" style="border:none;border-radius:0;background: #f8f8f8;padding: 6px 8px;" title="HTTP数据源">
            <i class="glyphicon glyphicon-cloud" style="vertical-align: middle;"></i>
        </button>`);
        toolbar.append(addHttpBtn);
        this.httpDatasourceDialog=new HttpDatasourceDialog(this.datasources);
        addHttpBtn.click(function(){
            _this.httpDatasourceDialog.show(function(name){
                const ds={name, type:'http'};
                const tree=new HttpTree(_this.treeContainer,_this.datasources,ds,_this.httpDatasourceDialog,_this.context);
                _this.datasources.push(tree);
            });
        });

        this.buildDatasources();
        return panel;
    }

    buildDatasources(){
        this.treeContainer.empty();
        for(let ds of this.datasources){
            if(ds.type==='jdbc'){
                new DatabaseTree(this.treeContainer,this.datasources,ds,this.datasourceDialog,this.context);
            }else if(ds.type==='spring'){
                new SpringTree(this.treeContainer,this.datasources,ds,this.springDialog,this.context);
            }else if(ds.type==='buildin'){
                new BuildinTree(this.treeContainer,this.datasources,ds,this.context);
            }else if(ds.type==='http'){
                new HttpTree(this.treeContainer,this.datasources,ds,this.httpDatasourceDialog,this.context);
            }
        }
    }
}