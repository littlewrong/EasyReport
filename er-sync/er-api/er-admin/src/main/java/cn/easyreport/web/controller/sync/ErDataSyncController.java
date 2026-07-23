package cn.easyreport.web.controller.sync;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import cn.easyreport.common.annotation.Log;
import cn.easyreport.common.core.controller.BaseController;
import cn.easyreport.common.core.domain.AjaxResult;
import cn.easyreport.common.enums.BusinessType;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.service.IErDataSyncService;
import cn.easyreport.common.core.page.TableDataInfo;

/**
 * 数据同步Controller
 *
 * @author easyreport
 * @date 2026-01-18
 */
@RestController
@RequestMapping("/system/datasync")
public class ErDataSyncController extends BaseController
{
    @Autowired
    private IErDataSyncService erDataSyncService;

    /**
     * 查询数据同步任务列表
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErDataSync erDataSync)
    {
        startPage();
        List<ErDataSync> list = erDataSyncService.selectErDataSyncList(erDataSync);
        return getDataTable(list);
    }

    /**
     * 获取数据同步任务详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:query')")
    @GetMapping(value = "/{syncId}")
    public AjaxResult getInfo(@PathVariable("syncId") Long syncId)
    {
        return success(erDataSyncService.selectErDataSyncBySyncId(syncId));
    }

    /**
     * 新增数据同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:add')")
    @Log(title = "数据同步", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErDataSync erDataSync)
    {
        if (!erDataSyncService.checkSyncNameUnique(erDataSync))
        {
            return error("新增同步任务'" + erDataSync.getSyncName() + "'失败，任务名称已存在");
        }
        erDataSync.setCreateBy(getUsername());
        return toAjax(erDataSyncService.insertErDataSync(erDataSync));
    }

    /**
     * 修改数据同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:edit')")
    @Log(title = "数据同步", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErDataSync erDataSync)
    {
        if (!erDataSyncService.checkSyncNameUnique(erDataSync))
        {
            return error("修改同步任务'" + erDataSync.getSyncName() + "'失败，任务名称已存在");
        }
        erDataSync.setUpdateBy(getUsername());
        return toAjax(erDataSyncService.updateErDataSync(erDataSync));
    }

    /**
     * 删除数据同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:remove')")
    @Log(title = "数据同步", businessType = BusinessType.DELETE)
    @DeleteMapping("/{syncIds}")
    public AjaxResult remove(@PathVariable Long[] syncIds)
    {
        return toAjax(erDataSyncService.deleteErDataSyncBySyncIds(syncIds));
    }

    /**
     * 执行同步任务（异步）
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:execute')")
    @Log(title = "数据同步", businessType = BusinessType.OTHER)
    @PostMapping("/execute/{syncId}")
    public AjaxResult executeSync(@PathVariable Long syncId)
    {
        Map<String, Object> result = erDataSyncService.executeSyncAsync(syncId);
        if ((Boolean) result.get("success"))
        {
            return success(result);
        }
        else
        {
            return error((String) result.get("message"));
        }
    }

    /**
     * 获取同步进度
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:query')")
    @GetMapping("/progress/{syncId}")
    public AjaxResult getProgress(@PathVariable Long syncId)
    {
        ErDataSync syncTask = erDataSyncService.getSyncProgress(syncId);
        if (syncTask == null)
        {
            return error("同步任务不存在");
        }
        return success(syncTask);
    }

    /**
     * 停止同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:execute')")
    @Log(title = "数据同步", businessType = BusinessType.OTHER)
    @PostMapping("/stop/{syncId}")
    public AjaxResult stopSync(@PathVariable Long syncId)
    {
        Map<String, Object> result = erDataSyncService.stopSync(syncId);
        if ((Boolean) result.get("success"))
        {
            return success(result);
        }
        else
        {
            return error((String) result.get("message"));
        }
    }

    /**
     * 预览同步（查询匹配的表列表）
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:query')")
    @GetMapping("/preview/{syncId}")
    public AjaxResult previewSync(@PathVariable Long syncId)
    {
        Map<String, Object> result = erDataSyncService.previewSync(syncId);
        return success(result);
    }

    /**
     * 查询同步日志列表
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:log')")
    @GetMapping("/log/list")
    public TableDataInfo logList(ErDataSyncLog erDataSyncLog)
    {
        startPage();
        List<ErDataSyncLog> list = erDataSyncService.selectErDataSyncLogList(erDataSyncLog);
        return getDataTable(list);
    }

    /**
     * 根据同步任务ID查询日志
     */
    @PreAuthorize("@ss.hasPermi('system:datasync:log')")
    @GetMapping("/log/{syncId}")
    public AjaxResult getLogBySyncId(@PathVariable Long syncId)
    {
        return success(erDataSyncService.selectErDataSyncLogBySyncId(syncId));
    }
}
