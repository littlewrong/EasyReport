/**
 * HTTP数据源对话框
 */
import {alert} from '../MsgBox.js';
import {setDirty} from '../Utils.js';

export default class HttpDatasourceDialog{
    constructor(datasources){
        this.datasources=datasources;
        this.dialog=$(`<div class="modal fade" role="dialog" aria-hidden="true" style="z-index: 10000">
            <div class="modal-dialog modal-lg">
                <div class="modal-content">
                    <div class="modal-header">
                        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">
                            &times;
                        </button>
                        <h4 class="modal-title">
                            HTTP数据源配置
                        </h4>
                    </div>
                    <div class="modal-body"></div>
                    <div class="modal-footer">
                    </div>
                </div>
            </div>
        </div>`);
        const body=this.dialog.find('.modal-body'),footer=this.dialog.find(".modal-footer");
        this.initBody(body,footer);
    }

    initBody(body,footer){
        // 数据源名称
        const dsRow=$(`<div class="row" style="margin-bottom: 10px;margin-right:6px;"><div class="col-md-2" style="padding: 0 10px 0 0px;text-align:right;margin-top:5px">数据源名称</div></div>`);
        const dsNameGroup=$(`<div class="col-md-10" style="padding: 0 10px 0 0px"></div>`);
        this.dsNameEditor=$(`<input type="text" class="form-control" style="font-size: 13px" placeholder="例如: MyAPI">`);
        dsNameGroup.append(this.dsNameEditor);
        dsRow.append(dsNameGroup);
        body.append(dsRow);

        // 提示信息
        const tipRow=$(`<div class="row" style="margin-bottom: 10px;margin-right:6px;">
            <div class="col-md-12" style="padding: 0 10px 0 15px;">
                <div class="alert alert-info" style="margin:0;padding:8px">
                    <small><strong>提示：</strong>数据源只是一个分组名称，具体的HTTP配置在添加数据集时设置</small>
                </div>
            </div>
        </div>`);
        body.append(tipRow);

        const _this=this;
        const saveButton=$(`<button type="button" class="btn btn-primary">保存</button>`);
        footer.append(saveButton);
        saveButton.click(function(){
            const name=_this.dsNameEditor.val();
            if(name===''){
                alert(`请输入数据源名称`);
                return;
            }
            let check=false;
            if(!_this.oldName || name!==_this.oldName){
                check=true;
            }
            if(check){
                for(let source of _this.datasources){
                    if(source.name===name){
                        alert(`数据源[${name}]已经存在`);
                        return;
                    }
                }
            }
            _this.onSave.call(this,name);
            setDirty();
            _this.dialog.modal('hide');
        });
    }

    show(onSave,ds){
        this.onSave=onSave;
        if(ds){
            this.oldName=ds.name;
            this.dsNameEditor.val(ds.name);
        }else{
            this.oldName=null;
            this.dsNameEditor.val('');
        }
        this.dialog.modal('show');
    }
}
