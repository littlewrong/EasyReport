package cn.easyreport.web.controller.sync;

import java.util.List;
import javax.servlet.http.HttpServletResponse;
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
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.service.IErDatasourceService;
import cn.easyreport.common.utils.poi.ExcelUtil;
import cn.easyreport.common.core.page.TableDataInfo;

/**
 * 数据源管理Controller
 *
 * @author easyreport
 * @date 2026-01-16
 */
@RestController
@RequestMapping("/system/datasource")
public class ErDatasourceController extends BaseController
{
    @Autowired
    private IErDatasourceService erDatasourceService;

    /**
     * 查询数据源管理列表
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErDatasource erDatasource)
    {
        startPage();
        List<ErDatasource> list = erDatasourceService.selectErDatasourceList(erDatasource);
        return getDataTable(list);
    }

    /**
     * 导出数据源管理列表
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:export')")
    @Log(title = "数据源管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ErDatasource erDatasource)
    {
        List<ErDatasource> list = erDatasourceService.selectErDatasourceList(erDatasource);
        ExcelUtil<ErDatasource> util = new ExcelUtil<ErDatasource>(ErDatasource.class);
        util.exportExcel(response, list, "数据源管理数据");
    }

    /**
     * 获取数据源管理详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:query')")
    @GetMapping(value = "/{datasourceId}")
    public AjaxResult getInfo(@PathVariable("datasourceId") Long datasourceId)
    {
        return success(erDatasourceService.selectErDatasourceByDatasourceId(datasourceId));
    }

    /**
     * 新增数据源管理
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:add')")
    @Log(title = "数据源管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErDatasource erDatasource)
    {
        if (!erDatasourceService.checkDatasourceNameUnique(erDatasource))
        {
            return error("新增数据源'" + erDatasource.getDatasourceName() + "'失败，数据源名称已存在");
        }
        erDatasource.setCreateBy(getUsername());
        return toAjax(erDatasourceService.insertErDatasource(erDatasource));
    }

    /**
     * 修改数据源管理
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:edit')")
    @Log(title = "数据源管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErDatasource erDatasource)
    {
        if (!erDatasourceService.checkDatasourceNameUnique(erDatasource))
        {
            return error("修改数据源'" + erDatasource.getDatasourceName() + "'失败，数据源名称已存在");
        }
        erDatasource.setUpdateBy(getUsername());
        return toAjax(erDatasourceService.updateErDatasource(erDatasource));
    }

    /**
     * 删除数据源管理
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:remove')")
    @Log(title = "数据源管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{datasourceIds}")
    public AjaxResult remove(@PathVariable Long[] datasourceIds)
    {
        return toAjax(erDatasourceService.deleteErDatasourceByDatasourceIds(datasourceIds));
    }

    /**
     * 获取数据源下拉选项（供架构同步、数据同步使用）
     */
    @PreAuthorize("@ss.hasAnyPermi('system:datasource:list,system:datasync:list,system:datatransfer:list')")
    @GetMapping("/options")
    public AjaxResult getOptions()
    {
        ErDatasource query = new ErDatasource();
        query.setStatus("0");
        List<ErDatasource> list = erDatasourceService.selectErDatasourceList(query);
        return success(list);
    }

    /**
     * 测试数据源连接
     */
    @PreAuthorize("@ss.hasPermi('system:datasource:test')")
    @Log(title = "数据源管理", businessType = BusinessType.OTHER)
    @PostMapping("/test")
    public AjaxResult testConnection(@RequestBody ErDatasource erDatasource)
    {
        String result = erDatasourceService.testConnection(erDatasource);
        if (result.contains("成功"))
        {
            return success(result);
        }
        else
        {
            return error(result);
        }
    }
}
