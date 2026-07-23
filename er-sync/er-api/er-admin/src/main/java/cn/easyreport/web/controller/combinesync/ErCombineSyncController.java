package cn.easyreport.web.controller.combinesync;

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
import cn.easyreport.combinesync.domain.ErCombineSync;
import cn.easyreport.combinesync.domain.ErCombineSyncLog;
import cn.easyreport.combinesync.domain.ErCombineSyncProgress;
import cn.easyreport.combinesync.service.IErCombineSyncService;
import cn.easyreport.common.core.page.TableDataInfo;

/**
 * 合并同步Controller
 *
 * @author easyreport
 * @date 2026-01-18
 */
@RestController
@RequestMapping("/system/combinesync")
public class ErCombineSyncController extends BaseController
{
    @Autowired
    private IErCombineSyncService erCombineSyncService;

    /**
     * 查询合并同步任务列表
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErCombineSync erCombineSync)
    {
        startPage();
        List<ErCombineSync> list = erCombineSyncService.selectErCombineSyncList(erCombineSync);
        return getDataTable(list);
    }

    /**
     * 获取合并同步任务详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:query')")
    @GetMapping(value = "/{combineId}")
    public AjaxResult getInfo(@PathVariable("combineId") Long combineId)
    {
        return success(erCombineSyncService.selectErCombineSyncByCombineId(combineId));
    }

    /**
     * 新增合并同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:add')")
    @Log(title = "合并同步", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErCombineSync erCombineSync)
    {
        if (!erCombineSyncService.checkCombineNameUnique(erCombineSync))
        {
            return error("新增合并任务'" + erCombineSync.getCombineName() + "'失败，任务名称已存在");
        }
        erCombineSync.setCreateBy(getUsername());
        return toAjax(erCombineSyncService.insertErCombineSync(erCombineSync));
    }

    /**
     * 修改合并同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:edit')")
    @Log(title = "合并同步", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErCombineSync erCombineSync)
    {
        if (!erCombineSyncService.checkCombineNameUnique(erCombineSync))
        {
            return error("修改合并任务'" + erCombineSync.getCombineName() + "'失败，任务名称已存在");
        }
        erCombineSync.setUpdateBy(getUsername());
        return toAjax(erCombineSyncService.updateErCombineSync(erCombineSync));
    }

    /**
     * 删除合并同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:remove')")
    @Log(title = "合并同步", businessType = BusinessType.DELETE)
    @DeleteMapping("/{combineIds}")
    public AjaxResult remove(@PathVariable Long[] combineIds)
    {
        return toAjax(erCombineSyncService.deleteErCombineSyncByCombineIds(combineIds));
    }

    /**
     * 执行合并同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:execute')")
    @Log(title = "合并同步", businessType = BusinessType.OTHER)
    @PostMapping("/execute/{combineId}")
    public AjaxResult executeCombineSync(@PathVariable Long combineId)
    {
        Map<String, Object> result = erCombineSyncService.executeCombineSync(combineId);
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
     * 预览合并同步（查询匹配的分库分表列表）
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:query')")
    @GetMapping("/preview/{combineId}")
    public AjaxResult previewCombineSync(@PathVariable Long combineId)
    {
        Map<String, Object> result = erCombineSyncService.previewCombineSync(combineId);
        return success(result);
    }

    /**
     * 查询表级最后同步时间戳
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:query')")
    @GetMapping("/progress/{combineId}")
    public AjaxResult progress(@PathVariable Long combineId)
    {
        List<ErCombineSyncProgress> list = erCombineSyncService.selectProgressByCombineId(combineId);
        return success(list);
    }

    /**
     * 从目标表最大时间戳刷新表级增量进度
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:execute')")
    @Log(title = "合并同步", businessType = BusinessType.OTHER)
    @PostMapping("/progress/refresh/{combineId}")
    public AjaxResult refreshProgress(@PathVariable Long combineId)
    {
        Map<String, Object> result = erCombineSyncService.refreshProgressFromTarget(combineId);
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
     * 获取合并同步任务的实时进度信息
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:query')")
    @GetMapping("/syncProgress/{combineId}")
    public AjaxResult syncProgress(@PathVariable Long combineId)
    {
        ErCombineSync task = erCombineSyncService.getSyncProgress(combineId);
        return success(task);
    }

    /**
     * 查询合并同步日志列表
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:log')")
    @GetMapping("/log/list")
    public TableDataInfo logList(ErCombineSyncLog erCombineSyncLog)
    {
        startPage();
        List<ErCombineSyncLog> list = erCombineSyncService.selectErCombineSyncLogList(erCombineSyncLog);
        return getDataTable(list);
    }

    /**
     * 根据合并任务ID查询日志
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:log')")
    @GetMapping("/log/{combineId}")
    public AjaxResult getLogByCombineId(@PathVariable Long combineId)
    {
        return success(erCombineSyncService.selectErCombineSyncLogByCombineId(combineId));
    }

    /**
     * 停止合并同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:combinesync:execute')")
    @Log(title = "合并同步", businessType = BusinessType.OTHER)
    @PostMapping("/stop/{combineId}")
    public AjaxResult stopCombineSync(@PathVariable Long combineId)
    {
        Map<String, Object> result = erCombineSyncService.stopCombineSync(combineId);
        if ((Boolean) result.get("success"))
        {
            return success(result);
        }
        else
        {
            return error((String) result.get("message"));
        }
    }
}
