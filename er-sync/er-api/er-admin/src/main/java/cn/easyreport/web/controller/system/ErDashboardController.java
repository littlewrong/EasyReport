package cn.easyreport.web.controller.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import cn.easyreport.common.core.controller.BaseController;
import cn.easyreport.common.core.domain.AjaxResult;
import cn.easyreport.system.service.IErDashboardService;

/**
 * 首页看板Controller
 *
 * @author easyreport
 */
@RestController
@RequestMapping("/system/dashboard")
public class ErDashboardController extends BaseController
{
    @Autowired
    private IErDashboardService dashboardService;

    /** KPI汇总 */
    @GetMapping("/summary")
    public AjaxResult summary()
    {
        return success(dashboardService.getSummary());
    }

    /** 近7天同步趋势 */
    @GetMapping("/syncTrend")
    public AjaxResult syncTrend()
    {
        return success(dashboardService.getSyncTrend());
    }

    /** 数据源类型分布 */
    @GetMapping("/dsTypeStats")
    public AjaxResult dsTypeStats()
    {
        return success(dashboardService.getDatasourceTypeStats());
    }

    /** 任务状态分布 */
    @GetMapping("/taskStatus")
    public AjaxResult taskStatus()
    {
        return success(dashboardService.getTaskStatusStats());
    }

    /** 最近同步记录 */
    @GetMapping("/recentLogs")
    public AjaxResult recentLogs()
    {
        return success(dashboardService.getRecentLogs());
    }
}
