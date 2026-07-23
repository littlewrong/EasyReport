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
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDataTransferProgress;
import cn.easyreport.sync.service.IErDataTransferService;
import cn.easyreport.common.core.page.TableDataInfo;

/**
 * 数据同步Controller
 *
 * @author easyreport
 * @date 2026-01-18
 */
@RestController
@RequestMapping("/system/datatransfer")
public class ErDataTransferController extends BaseController
{
    @Autowired
    private IErDataTransferService erDataTransferService;

    /**
     * 查询数据同步任务列表
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErDataTransfer erDataTransfer)
    {
        startPage();
        List<ErDataTransfer> list = erDataTransferService.selectErDataTransferList(erDataTransfer);
        return getDataTable(list);
    }

    /**
     * 获取数据同步任务详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:query')")
    @GetMapping(value = "/{transferId}")
    public AjaxResult getInfo(@PathVariable("transferId") Long transferId)
    {
        return success(erDataTransferService.selectErDataTransferByTransferId(transferId));
    }

    /**
     * 新增数据同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:add')")
    @Log(title = "数据同步", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErDataTransfer erDataTransfer)
    {
        if (!erDataTransferService.checkTransferNameUnique(erDataTransfer))
        {
            return error("新增同步任务'" + erDataTransfer.getTransferName() + "'失败，任务名称已存在");
        }
        erDataTransfer.setCreateBy(getUsername());
        return toAjax(erDataTransferService.insertErDataTransfer(erDataTransfer));
    }

    /**
     * 修改数据同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:edit')")
    @Log(title = "数据同步", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErDataTransfer erDataTransfer)
    {
        if (!erDataTransferService.checkTransferNameUnique(erDataTransfer))
        {
            return error("修改同步任务'" + erDataTransfer.getTransferName() + "'失败，任务名称已存在");
        }
        erDataTransfer.setUpdateBy(getUsername());
        return toAjax(erDataTransferService.updateErDataTransfer(erDataTransfer));
    }

    /**
     * 删除数据同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:remove')")
    @Log(title = "数据同步", businessType = BusinessType.DELETE)
    @DeleteMapping("/{transferIds}")
    public AjaxResult remove(@PathVariable Long[] transferIds)
    {
        return toAjax(erDataTransferService.deleteErDataTransferByTransferIds(transferIds));
    }

    /**
     * 执行同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:execute')")
    @Log(title = "数据同步", businessType = BusinessType.OTHER)
    @PostMapping("/execute/{transferId}")
    public AjaxResult executeTransfer(@PathVariable Long transferId)
    {
        Map<String, Object> result = erDataTransferService.executeTransfer(transferId);
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
    @PreAuthorize("@ss.hasPermi('system:datatransfer:query')")
    @GetMapping("/preview/{transferId}")
    public AjaxResult previewTransfer(@PathVariable Long transferId)
    {
        Map<String, Object> result = erDataTransferService.previewTransfer(transferId);
        return success(result);
    }

    /**
     * 查询表级最后同步时间戳
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:query')")
    @GetMapping("/progress/{transferId}")
    public AjaxResult progress(@PathVariable Long transferId)
    {
        List<ErDataTransferProgress> list = erDataTransferService.selectProgressByTransferId(transferId);
        return success(list);
    }

    /**
     * 从目标表最大时间戳刷新表级增量进度
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:execute')")
    @Log(title = "数据同步", businessType = BusinessType.OTHER)
    @PostMapping("/progress/refresh/{transferId}")
    public AjaxResult refreshProgress(@PathVariable Long transferId)
    {
        Map<String, Object> result = erDataTransferService.refreshProgressFromTarget(transferId);
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
     * 获取同步任务的实时进度信息（异步执行时查询）
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:query')")
    @GetMapping("/syncProgress/{transferId}")
    public AjaxResult syncProgress(@PathVariable Long transferId)
    {
        ErDataTransfer transfer = erDataTransferService.getSyncProgress(transferId);
        return success(transfer);
    }

    /**
     * 查询同步日志列表
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:log')")
    @GetMapping("/log/list")
    public TableDataInfo logList(ErDataTransferLog erDataTransferLog)
    {
        startPage();
        List<ErDataTransferLog> list = erDataTransferService.selectErDataTransferLogList(erDataTransferLog);
        return getDataTable(list);
    }

    /**
     * 根据同步任务ID查询日志
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:log')")
    @GetMapping("/log/{transferId}")
    public AjaxResult getLogByTransferId(@PathVariable Long transferId)
    {
        return success(erDataTransferService.selectErDataTransferLogByTransferId(transferId));
    }

    /**
     * 停止同步任务
     */
    @PreAuthorize("@ss.hasPermi('system:datatransfer:execute')")
    @Log(title = "数据同步", businessType = BusinessType.OTHER)
    @PostMapping("/stop/{transferId}")
    public AjaxResult stopTransfer(@PathVariable Long transferId)
    {
        Map<String, Object> result = erDataTransferService.stopTransfer(transferId);
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
