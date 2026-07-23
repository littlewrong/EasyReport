/**
 * SSE流式导出进度显示
 * 使用Server-Sent Events接收后端进度推送
 */

export default function StreamingExportProgress() {

    // 创建进度弹窗
    function buildProgressModal() {
        if ($('#streamingExportProgressModal').length > 0) {
            return $('#streamingExportProgressModal');
        }

        const modal = $(`
        <div class="modal fade" id="streamingExportProgressModal" tabindex="-1" role="dialog" aria-hidden="true" data-backdrop="static" data-keyboard="false" style="z-index:10600;">
          <div class="modal-dialog" style="width:500px;">
            <div class="modal-content">
              <div class="modal-header">
                <h4 class="modal-title">流式导出进度</h4>
              </div>
              <div class="modal-body">
                <div class="progress" style="height:25px;margin-bottom:15px;">
                  <div class="progress-bar progress-bar-striped active" role="progressbar"
                       id="exportProgressBar" style="width:0%;line-height:25px;font-size:14px;">
                    0%
                  </div>
                </div>
                <div id="exportProgressMessage" style="text-align:center;color:#666;margin-bottom:10px;">
                  准备导出...
                </div>
                <div id="exportProgressDetails" style="font-size:12px;color:#999;text-align:center;">

                </div>
                <div id="exportCompleteActions" style="margin-top:20px;text-align:center;display:none;">
                  <button type="button" class="btn btn-success" id="downloadExportBtn">
                    <i class="glyphicon glyphicon-download"></i> 下载文件
                  </button>
                  <button type="button" class="btn btn-default" id="closeProgressModalBtn" data-dismiss="modal">
                    关闭
                  </button>
                </div>
                <div id="exportErrorActions" style="margin-top:20px;text-align:center;display:none;">
                  <button type="button" class="btn btn-default" data-dismiss="modal">
                    关闭
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
        `);

        $('body').append(modal);
        return modal;
    }

    // 启动流式导出（支持SSE进度）
    function startStreamingExport(url) {
        const modal = buildProgressModal();
        const progressBar = $('#exportProgressBar');
        const messageDiv = $('#exportProgressMessage');
        const detailsDiv = $('#exportProgressDetails');
        const completeActions = $('#exportCompleteActions');
        const errorActions = $('#exportErrorActions');

        // 重置状态
        progressBar.css('width', '0%').text('0%').removeClass('progress-bar-success progress-bar-danger');
        messageDiv.text('准备导出...');
        detailsDiv.text('');
        completeActions.hide();
        errorActions.hide();

        modal.modal('show');

        // 第一步：调用异步导出接口获取taskId
        $.ajax({
            url: url.replace('/streamingExport', '/streamingExportAsync'),
            type: 'GET',
            dataType: 'json',
            success: function(response) {
                const taskId = response.taskId;

                // 第二步：使用EventSource监听进度
                const eventSource = new EventSource(window._server + '/exportProgress?taskId=' + taskId);

                eventSource.addEventListener('progress', function(event) {
                    const data = JSON.parse(event.data);

                    // 更新进度条
                    const percent = data.percent || 0;
                    progressBar.css('width', percent + '%').text(percent + '%');

                    // 更新消息
                    messageDiv.text(data.message || '导出中...');

                    // 更新详细信息
                    if (data.current && data.total) {
                        detailsDiv.text(`已处理: ${data.current} / ${data.total} 条数据`);
                    }

                    // 检查是否完成
                    if (data.status === 'completed') {
                        eventSource.close();
                        progressBar.addClass('progress-bar-success');
                        messageDiv.text('✓ 导出完成！');

                        // 显示下载按钮
                        completeActions.show();
                        $('#downloadExportBtn').off('click').on('click', function() {
                            window.open(window._server + data.downloadUrl, '_blank');
                            modal.modal('hide');
                        });
                    } else if (data.status === 'failed') {
                        eventSource.close();
                        progressBar.addClass('progress-bar-danger').css('width', '100%').text('失败');
                        messageDiv.text('✗ 导出失败');
                        detailsDiv.text(data.error || '未知错误');
                        errorActions.show();
                    }
                });

                eventSource.addEventListener('error', function(event) {
                    eventSource.close();
                    progressBar.addClass('progress-bar-danger').css('width', '100%').text('错误');
                    messageDiv.text('✗ 连接失败');
                    detailsDiv.text('无法连接到服务器，请检查网络或刷新页面重试');
                    errorActions.show();
                });

                // 模态框关闭时停止监听
                modal.off('hidden.bs.modal').on('hidden.bs.modal', function() {
                    if (eventSource.readyState !== EventSource.CLOSED) {
                        eventSource.close();
                    }
                });
            },
            error: function(xhr, status, error) {
                progressBar.addClass('progress-bar-danger').css('width', '100%').text('失败');
                messageDiv.text('✗ 启动导出失败');
                detailsDiv.text('错误: ' + (xhr.responseText || error));
                errorActions.show();
            }
        });
    }

    return {
        start: startStreamingExport
    };
}
