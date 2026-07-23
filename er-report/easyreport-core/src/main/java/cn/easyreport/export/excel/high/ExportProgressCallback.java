/*******************************************************************************
 * Copyright 2017 Bstek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package cn.easyreport.export.excel.high;

/**
 * 导出进度回调接口
 * 用于大数据量流式导出时的进度跟踪
 *
 * @since 2025年1月15日
 */
public interface ExportProgressCallback {
    /**
     * 进度更新回调
     * @param current 当前已处理的数据量
     * @param total 总数据量
     * @param percent 完成百分比 (0-100)
     * @param message 进度描述消息
     */
    void onProgress(int current, int total, int percent, String message);

    /**
     * 导出完成回调
     * @param totalRows 实际导出的总行数
     */
    void onComplete(int totalRows);

    /**
     * 导出失败回调
     * @param error 错误信息
     */
    void onError(String error);
}
