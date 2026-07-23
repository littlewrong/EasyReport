package cn.easyreport.system.service.impl;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import cn.easyreport.system.mapper.ErDashboardMapper;
import cn.easyreport.system.service.IErDashboardService;

/**
 * 首页看板Service实现
 *
 * @author easyreport
 */
@Service
public class ErDashboardServiceImpl implements IErDashboardService
{
    @Autowired
    private ErDashboardMapper dashboardMapper;

    @Override
    public Map<String, Object> getSummary()
    {
        Map<String, Object> result = new HashMap<>();
        // 数据源
        result.put("datasourceTotal", dashboardMapper.countDatasource());
        result.put("datasourceConnected", dashboardMapper.countDatasourceConnected());
        result.put("datasourceFailed", dashboardMapper.countDatasourceFailed());
        // 同步任务
        result.put("taskTotal", dashboardMapper.countTransferTask());
        result.put("taskRunning", dashboardMapper.countTransferRunning());
        // 今日同步
        result.put("todaySyncTotal", dashboardMapper.countTodaySyncTotal());
        result.put("todaySyncSuccess", dashboardMapper.countTodaySyncSuccess());
        result.put("todaySyncFail", dashboardMapper.countTodaySyncFail());
        result.put("todaySyncRows", dashboardMapper.countTodaySyncRows());
        return result;
    }

    @Override
    public Map<String, Object> getSyncTrend()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("list", dashboardMapper.selectSyncTrend7Days());
        return result;
    }

    @Override
    public Map<String, Object> getDatasourceTypeStats()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("list", dashboardMapper.selectDatasourceTypeStats());
        return result;
    }

    @Override
    public Map<String, Object> getTaskStatusStats()
    {
        Map<String, Object> result = new HashMap<>();
        // 合并数据同步和结构同步的状态
        List<Map<String, Object>> transferStats = dashboardMapper.selectTransferStatusStats();
        List<Map<String, Object>> syncStats = dashboardMapper.selectSyncStatusStats();

        // 按状态合并计数
        Map<String, Integer> merged = new LinkedHashMap<>();
        merged.put("0", 0); // 待同步
        merged.put("1", 0); // 同步中
        merged.put("2", 0); // 成功
        merged.put("3", 0); // 失败

        for (Map<String, Object> row : transferStats) {
            String status = String.valueOf(row.get("status"));
            int count = ((Number) row.get("count")).intValue();
            merged.merge(status, count, Integer::sum);
        }
        for (Map<String, Object> row : syncStats) {
            String status = String.valueOf(row.get("status"));
            int count = ((Number) row.get("count")).intValue();
            merged.merge(status, count, Integer::sum);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, String> labelMap = new LinkedHashMap<>();
        labelMap.put("0", "待同步");
        labelMap.put("1", "同步中");
        labelMap.put("2", "已成功");
        labelMap.put("3", "已失败");

        for (Map.Entry<String, String> entry : labelMap.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", entry.getValue());
            item.put("value", merged.getOrDefault(entry.getKey(), 0));
            list.add(item);
        }
        result.put("list", list);
        return result;
    }

    @Override
    public Map<String, Object> getRecentLogs()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("list", dashboardMapper.selectRecentTransferLogs());
        return result;
    }
}
