/**
 * 轮询方式导出进度显示（替代SSE，更稳定）
 * 通过定时轮询REST接口获取后端进度
 */

export default function StreamingExportProgressPolling() {

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

    // 启动流式导出（轮询方式）
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

        let pollInterval = null;
        let taskId = null;

        // 第一步：调用异步导出接口获取taskId
        $.ajax({
            url: url.replace('/streamingExport', '/streamingExportAsync'),
            type: 'GET',
            dataType: 'json',
            success: function(response) {
                taskId = response.taskId;
                console.log('[Polling] 获取到taskId:', taskId);

                // 第二步：启动轮询
                pollInterval = setInterval(function() {
                    pollProgress(taskId);
                }, 1000); // 每1000ms (1秒) 轮询一次

                // 立即执行一次
                pollProgress(taskId);
            },
            error: function(xhr, status, error) {
                progressBar.addClass('progress-bar-danger').css('width', '100%').text('失败');
                messageDiv.text('✗ 启动导出失败');
                detailsDiv.text('错误: ' + (xhr.responseText || error));
                errorActions.show();
            }
        });

        // 轮询进度
        function pollProgress(taskId) {
            $.ajax({
                url: window._server + '/exportProgressPoll?taskId=' + taskId,
                type: 'GET',
                dataType: 'json',
                cache: false, // 禁用缓存
                success: function(data) {
                    console.log('[Polling] 进度更新:', data.percent + '%', data.status);

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
                        clearInterval(pollInterval);
                        pollInterval = null;

                        progressBar.addClass('progress-bar-success');
                        messageDiv.text('✓ 导出完成！');

                        // 显示下载按钮
                        completeActions.show();
                        $('#downloadExportBtn').off('click').on('click', function() {
                            window.open(window._server + data.downloadUrl, '_blank');
                            modal.modal('hide');
                        });
                    } else if (data.status === 'failed') {
                        clearInterval(pollInterval);
                        pollInterval = null;

                        progressBar.addClass('progress-bar-danger').css('width', '100%').text('失败');
                        messageDiv.text('✗ 导出失败');
                        detailsDiv.text(data.error || '未知错误');
                        errorActions.show();
                    }
                },
                error: function(xhr, status, error) {
                    console.error('[Polling] 轮询错误:', status, error);

                    // 任务未找到或其他错误，停止轮询
                    if (xhr.status === 404 || xhr.status === 400) {
                        clearInterval(pollInterval);
                        pollInterval = null;

                        progressBar.addClass('progress-bar-danger').css('width', '100%').text('错误');
                        messageDiv.text('✗ 获取进度失败');
                        detailsDiv.text('任务未找到或已过期');
                        errorActions.show();
                    }
                    // 其他错误（如网络问题），继续重试
                }
            });
        }

        // 模态框关闭时停止轮询
        modal.off('hidden.bs.modal').on('hidden.bs.modal', function() {
            if (pollInterval) {
                clearInterval(pollInterval);
                pollInterval = null;
                console.log('[Polling] 停止轮询');
            }
        });
    }

    return {
        start: startStreamingExport
    };
}
