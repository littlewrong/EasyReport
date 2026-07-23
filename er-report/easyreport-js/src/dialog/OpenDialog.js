/**
 * Created by Jacky.Gao on 2017-02-12.
 */
import {formatDate,resetDirty} from '../Utils.js';
import {alert,confirm} from '../MsgBox.js';

export default class OpenDialog{
    constructor(context){
        this.context=context;
        this.reportFilesData={};
        this.dialog=$(`<div class="modal fade" role="dialog" aria-hidden="true" style="z-index: 10000">
            <div class="modal-dialog modal-lg">
                <div class="modal-content">
                    <div class="modal-header">
                        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">
                            &times;
                        </button>
                        <h4 class="modal-title">
                            ${window.i18n.dialog.open.title}
                        </h4>
                    </div>
                    <div class="modal-body"></div>
                    <div class="modal-footer"></div>
                </div>
            </div>
        </div>`);
        const body=this.dialog.find('.modal-body'),footer=this.dialog.find(".modal-footer");
        this.initBody(body);
    }
    initBody(body){
        const providerGroup=$(`<div class="form-group"><label style="display:inline-block;width:80px;">${window.i18n.dialog.open.source}</label></div>`);
        this.providerSelect=$(`<select class="form-control" style="display: inline-block;width:450px;">`);
        providerGroup.append(this.providerSelect);
        body.append(providerGroup);

        // Add search input field
        const searchGroup=$(`<div class="form-group" style="margin-top: 10px;"><label style="display:inline-block;width:80px;">${window.i18n.dialog.open.search || '检索：'}</label></div>`);
        this.searchInput=$(`<input type="text" class="form-control" placeholder="${window.i18n.dialog.open.searchPlaceholder || '输入文件名进行检索...'}" style="display: inline-block;width:450px;">`);
        searchGroup.append(this.searchInput);
        const newBtn=$(`<button type="button" class="btn btn-success btn-sm" style="margin-left:10px;"><i class="glyphicon glyphicon-plus"></i> ${window.i18n.dialog.open.newFile || '新建报表'}</button>`);
        searchGroup.append(newBtn);
        this.newBtn=newBtn;
        body.append(searchGroup);

        const tableContainer=$(`<div style="height:350px;overflow: auto"></div>`);
        body.append(tableContainer);
        const fileTable=$(`<table class="table table-bordered"><thead><tr style="background: #f4f4f4;height: 30px;">
            <td style="vertical-align: middle">${window.i18n.dialog.open.fileName}</td>
            <td style="width: 150px;vertical-align: middle">${window.i18n.dialog.open.modDate}</td>
            <td style="width:50px;vertical-align: middle;text-align:center;">${window.i18n.dialog.open.open}</td>
            <td style="width:50px;vertical-align: middle;text-align:center;">${window.i18n.dialog.open.edit || '编辑'}</td>
            <td style="width:60px;vertical-align: middle;text-align:center;">${window.i18n.dialog.open.rename || '重命名'}</td>
            <td style="width:60px;vertical-align: middle;text-align:center;">${window.i18n.dialog.open.download || '下载'}</td>
            <td style="width:50px;vertical-align: middle;text-align:center;">${window.i18n.dialog.open.del}</td></tr></thead></table>`);
        this.fileTableBody=$(`<tbody></tbody>`);
        fileTable.append(this.fileTableBody);
        tableContainer.append(fileTable);
        const _this=this;
        this.providerSelect.change(function(){
            let value=$(this).val();
            if(!value || value===''){
                return;
            }
            _this.currentProviderPrefix=value;
            _this.currentReportFiles=_this.reportFilesData[value];
            _this.searchInput.val(''); // Clear search input when switching providers
            _this.renderFileList();
        });

        // Add search input listener
        this.searchInput.on('input', function(){
            _this.renderFileList();
        });

        this.newBtn.click(function(){
            _this.createNewFile();
        });
    }

    // Extract file list rendering logic to a separate method
    renderFileList(searchKeyword){
        this.fileTableBody.empty();

        if(!this.currentReportFiles){
            return;
        }

        const keyword = searchKeyword || this.searchInput.val() || '';
        const filteredFiles = keyword.trim() === ''
            ? this.currentReportFiles
            : this.currentReportFiles.filter(file =>
                file.name.toLowerCase().indexOf(keyword.toLowerCase()) !== -1
              );

        if(filteredFiles.length === 0){
            const noResultRow = $(`<tr><td colspan="7" style="text-align:center;color:#999;padding:20px;">${window.i18n.dialog.open.noResult || '没有找到匹配的文件'}</td></tr>`);
            this.fileTableBody.append(noResultRow);
            return;
        }

        const _this = this;
        const value = this.currentProviderPrefix;
        const reportFiles = this.currentReportFiles;

        for(let file of filteredFiles){
            let tr=$(`<tr style="height: 35px;"></tr>`);
            _this.fileTableBody.append(tr);
            tr.append(`<td style="vertical-align: middle;">${file.name}</td>`);
            tr.append(`<td style="vertical-align: middle;">${formatDate(file.updateDate)}</td>`);
            const encodedFile=value+encodeURI(encodeURI(file.name));

            let openCol=$(`<td style="vertical-align: middle;text-align:center;"></td>`);
            tr.append(openCol);
            let openIcon=$(`<a href="###"><i class="glyphicon glyphicon-folder-open" style="color: #008ed3;font-size: 14pt"></i></a>`);
            openCol.append(openIcon);
            openIcon.click(function(){
                confirm(`${window.i18n.dialog.open.openConfirm}[${file.name}]？`,function(){
                    let path=window._server+"/designer?_u="+encodedFile;
                    window.open(path,"_self");
                });
            });

            let editCol=$(`<td style="vertical-align: middle;text-align:center;"></td>`);
            tr.append(editCol);
            let editIcon=$(`<a href="###"><i class="glyphicon glyphicon-edit" style="color: #28a745;font-size: 14pt"></i></a>`);
            editCol.append(editIcon);
            editIcon.click(function(){
                _this.openEditDialog(value, file);
            });

            let renameCol=$(`<td style="vertical-align: middle;text-align:center;"></td>`);
            tr.append(renameCol);
            let renameIcon=$(`<a href="###"><i class="glyphicon glyphicon-pencil" style="color: #f0ad4e;font-size: 14pt"></i></a>`);
            renameCol.append(renameIcon);
            renameIcon.click(function(){
                _this.renameFile(value, file);
            });

            let downloadCol=$(`<td style="vertical-align: middle;text-align:center;"></td>`);
            tr.append(downloadCol);
            let downloadIcon=$(`<a href="###"><i class="glyphicon glyphicon-download-alt" style="color: #008ed3;font-size: 14pt"></i></a>`);
            downloadCol.append(downloadIcon);
            downloadIcon.click(function(){
                let path=window._server+"/designer/downloadReportFile?file="+encodedFile;
                window.open(path,"_blank");
            });

            let deleteCol=$(`<td style="vertical-align: middle;text-align:center;"></td>`);
            tr.append(deleteCol);
            let deleteIcon=$(`<a href="###"><i class="glyphicon glyphicon-trash" style="color: red;font-size: 14pt"></i></a>`);
            deleteCol.append(deleteIcon);

            deleteIcon.click(function(){
                confirm(`${window.i18n.dialog.open.delConfirm}`+file.name,function(){
                    let fullFile=value+file.name;
                    $.ajax({
                        type:'POST',
                        data:{file:fullFile},
                        url:window._server+"/designer/deleteReportFile",
                        success:function(){
                            tr.remove();
                            let index=reportFiles.indexOf(file);
                            reportFiles.splice(index,1);
                            // Update filtered list display
                            _this.renderFileList();
                        },
                        error:function(response){
                            if(response && response.responseText){
                                alert("服务端错误："+response.responseText+"");
                            }else{
                                alert(`${window.i18n.dialog.open.delFail}`);
                            }
                        }
                    });
                });
            });
        }
    }
    show(){
        this.providerSelect.empty();
        this.fileTableBody.empty();
        this.reportFilesData={};
        const _this=this;
        $.ajax({
            url:window._server+'/designer/loadReportProviders',
            success:function(providers){
                for(let provider of providers){
                    let {reportFiles,name,prefix}=provider;
                    _this.reportFilesData[prefix]=reportFiles;
                    _this.providerSelect.append(`<option value="${prefix}">${name}</option>`);
                }
                _this.providerSelect.trigger('change');
            },
            error:function(response){
                if(response && response.responseText){
                    alert("服务端错误："+response.responseText+"");
                }else{
                    alert(`${window.i18n.dialog.open.loadFail}`);
                }
            }
        });
        this.dialog.modal('show');
    }

    openEditDialog(providerPrefix, file){
        const _this = this;
        const encodedFile = providerPrefix + encodeURI(encodeURI(file.name));

        // Create edit dialog
        const editDialog = $(`<div class="modal fade" role="dialog" aria-hidden="true" style="z-index: 10001">
            <div class="modal-dialog modal-lg" style="width: 90%;">
                <div class="modal-content">
                    <div class="modal-header">
                        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                        <h4 class="modal-title">${window.i18n.dialog.open.editTitle || '编辑报表'}: ${file.name}</h4>
                    </div>
                    <div class="modal-body" style="padding: 15px;">
                        <textarea id="xmlEditor" style="width:100%;height:500px;font-family:monospace;font-size:12px;"></textarea>
                    </div>
                    <div class="modal-footer">
                        <button type="button" class="btn btn-default" data-dismiss="modal">${(window.i18n.dialog.button && window.i18n.dialog.button.cancel) || '取消'}</button>
                        <button type="button" class="btn btn-primary" id="saveXmlBtn">${(window.i18n.dialog.button && window.i18n.dialog.button.save) || '保存'}</button>
                    </div>
                </div>
            </div>
        </div>`);

        $('body').append(editDialog);

        // Load XML content
        $.ajax({
            url: window._server + '/designer/loadReportFile',
            data: { file: encodedFile },
            type: 'GET',
            success: function(xmlContent){
                $('#xmlEditor').val(xmlContent);
            },
            error: function(response){
                if(response && response.responseText){
                    alert("加载失败：" + response.responseText);
                } else {
                    alert(window.i18n.dialog.open.loadFileFail || '加载报表文件失败');
                }
                editDialog.modal('hide');
            }
        });

        // Save button handler
        editDialog.find('#saveXmlBtn').click(function(){
            const xmlContent = $('#xmlEditor').val();
            if(!xmlContent || xmlContent.trim() === ''){
                alert(window.i18n.dialog.open.emptyContent || '内容不能为空');
                return;
            }

            confirm(window.i18n.dialog.open.saveConfirm || `确定保存对 ${file.name} 的修改吗？`, function(){
                $.ajax({
                    url: window._server + '/designer/saveReportFile',
                    data: {
                        file: providerPrefix + file.name,
                        content: xmlContent
                    },
                    type: 'POST',
                    success: function(){
                        alert(window.i18n.dialog.open.saveSuccess || '保存成功');
                        editDialog.modal('hide');
                        // Store as formatted string to match server format; formatDate() throws on raw timestamps when no format arg is passed in renderFileList
                        file.updateDate = formatDate(new Date(), 'yyyy-MM-dd HH:mm:ss');
                        _this.renderFileList();
                    },
                    error: function(response){
                        if(response && response.responseText){
                            alert("保存失败：" + response.responseText);
                        } else {
                            alert(window.i18n.dialog.open.saveFail || '保存报表文件失败');
                        }
                    }
                });
            });
        });

        // Show dialog and cleanup on close
        editDialog.modal('show');
        editDialog.on('hidden.bs.modal', function(){
            editDialog.remove();
        });
    }

    createNewFile(){
        const _this=this;
        const providerPrefix=this.currentProviderPrefix;
        if(!providerPrefix){
            alert(window.i18n.dialog.open.noProvider || '请先选择报表来源');
            return;
        }
        const reportFiles=this.currentReportFiles||[];
        const inputName=window.prompt(window.i18n.dialog.open.newFilePrompt || '请输入新报表文件名（无需扩展名）：','');
        if(inputName===null){
            return;
        }
        let fileName=inputName.trim();
        if(fileName===''){
            alert(window.i18n.dialog.open.emptyName || '文件名不能为空');
            return;
        }
        if(!/\.(xml|ureport\.xml)$/i.test(fileName)){
            fileName=fileName+'.ureport.xml';
        }
        if(/[\\/:*?"<>|]/.test(fileName)){
            alert(window.i18n.dialog.open.invalidName || '文件名包含非法字符');
            return;
        }
        for(let f of reportFiles){
            if(f.name===fileName){
                alert(window.i18n.dialog.open.fileExists || '同名文件已存在');
                return;
            }
        }
        const cellStyle='<cell-style font-size="10" align="center" valign="middle"></cell-style>';
        let cellsXml='';
        const colLetters=['A','B','C','D'];
        for(let r=1;r<=3;r++){
            for(let c=1;c<=4;c++){
                cellsXml+=`<cell expand="None" name="${colLetters[c-1]}${r}" col="${c}" row="${r}"><simple-value><![CDATA[]]></simple-value>${cellStyle}</cell>`;
            }
        }
        const blankXml='<?xml version="1.0" encoding="UTF-8"?>'
            +'<ureport xmlns="http://www.example.org/ureport2" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.example.org/ureport2 http://www.example.org/ureport2 ">'
            +cellsXml
            +'<row row-number="1" height="18"/><row row-number="2" height="18"/><row row-number="3" height="18"/>'
            +'<column col-number="1" width="80"/><column col-number="2" width="80"/><column col-number="3" width="80"/><column col-number="4" width="80"/>'
            +'<paper type="A4" orientation="portrait" paging-mode="fitpage"></paper>'
            +'</ureport>';
        $.ajax({
            url: window._server+'/designer/saveReportFile',
            type:'POST',
            data:{file:providerPrefix+fileName,content:blankXml},
            success:function(){
                reportFiles.push({name:fileName,updateDate:formatDate(new Date(),'yyyy-MM-dd HH:mm:ss')});
                reportFiles.sort((a,b)=>(b.updateDate||'').localeCompare(a.updateDate||''));
                _this.renderFileList();
            },
            error:function(response){
                if(response && response.responseText){
                    alert("创建失败："+response.responseText);
                }else{
                    alert(window.i18n.dialog.open.createFail || '创建报表文件失败');
                }
            }
        });
    }

    renameFile(providerPrefix, file){
        const _this=this;
        const reportFiles=this.currentReportFiles||[];
        const inputName=window.prompt(window.i18n.dialog.open.renamePrompt || '请输入新文件名：', file.name);
        if(inputName===null){
            return;
        }
        let newName=inputName.trim();
        if(newName===''){
            alert(window.i18n.dialog.open.emptyName || '文件名不能为空');
            return;
        }
        if(newName===file.name){
            return;
        }
        if(/[\\/:*?"<>|]/.test(newName)){
            alert(window.i18n.dialog.open.invalidName || '文件名包含非法字符');
            return;
        }
        for(let f of reportFiles){
            if(f.name===newName){
                alert(window.i18n.dialog.open.fileExists || '同名文件已存在');
                return;
            }
        }
        const oldEncoded=providerPrefix+encodeURI(encodeURI(file.name));
        $.ajax({
            url: window._server+'/designer/loadReportFile',
            type:'GET',
            data:{file:oldEncoded},
            success:function(xmlContent){
                $.ajax({
                    url: window._server+'/designer/saveReportFile',
                    type:'POST',
                    data:{file:providerPrefix+newName,content:xmlContent},
                    success:function(){
                        $.ajax({
                            type:'POST',
                            url:window._server+'/designer/deleteReportFile',
                            data:{file:providerPrefix+file.name},
                            success:function(){
                                file.name=newName;
                                file.updateDate=formatDate(new Date(),'yyyy-MM-dd HH:mm:ss');
                                _this.renderFileList();
                            },
                            error:function(response){
                                alert((window.i18n.dialog.open.renameFail || '重命名失败：旧文件删除失败 ')+(response && response.responseText ? response.responseText : ''));
                                _this.renderFileList();
                            }
                        });
                    },
                    error:function(response){
                        alert((window.i18n.dialog.open.renameFail || '重命名失败：保存新文件失败 ')+(response && response.responseText ? response.responseText : ''));
                    }
                });
            },
            error:function(response){
                alert((window.i18n.dialog.open.renameFail || '重命名失败：加载原文件失败 ')+(response && response.responseText ? response.responseText : ''));
            }
        });
    }
}
