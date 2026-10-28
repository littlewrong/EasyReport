package cn.easyreport.web.controller.sync;

import cn.easyreport.common.core.domain.AjaxResult;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.service.IErDatasourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

public class ErDatasourceOptionsPrivacyTest
{
    @Test
    public void optionsExposeOnlySelectorMetadataWithoutMutatingConnectionCredentials() throws Exception
    {
        ErDatasource source = new ErDatasource();
        source.setDatasourceId(7L);
        source.setDatasourceName("Example source");
        source.setDatasourceType("MYSQL");
        source.setHost("private-source.example.invalid");
        source.setUsername("private-test-user");
        source.setPassword("private-test-password");
        source.setJdbcUrl("jdbc:mysql://private-source.example.invalid:3306/demo?password=private-url-secret");
        source.setConnectionParams("password=private-parameter-secret");
        source.setTestMessage("private-driver-detail");
        source.setRemark("private-remark");

        IErDatasourceService service = (IErDatasourceService) Proxy.newProxyInstance(
                IErDatasourceService.class.getClassLoader(), new Class<?>[] {IErDatasourceService.class},
                (proxy, method, arguments) -> {
                    if ("selectErDatasourceList".equals(method.getName()))
                    {
                        assertEquals("0", ((ErDatasource) arguments[0]).getStatus());
                        return Arrays.asList(source);
                    }
                    throw new AssertionError("Unexpected service call: " + method.getName());
                });
        ErDatasourceController controller = new ErDatasourceController();
        Field field = ErDatasourceController.class.getDeclaredField("erDatasourceService");
        field.setAccessible(true);
        field.set(controller, service);

        AjaxResult response = controller.getOptions();
        assertEquals(200, response.get("code"));
        List<?> options = (List<?>) response.get("data");
        assertEquals(1, options.size());
        Map<?, ?> option = (Map<?, ?>) options.get(0);
        assertEquals(new HashSet<>(Arrays.asList("datasourceId", "datasourceName", "datasourceType")), option.keySet());
        assertEquals(7L, option.get("datasourceId"));
        assertEquals("Example source", option.get("datasourceName"));
        assertEquals("MYSQL", option.get("datasourceType"));
        assertFalse(new ObjectMapper().writeValueAsString(response).contains("private-"));
        assertEquals("private-test-password", source.getPassword());
        assertEquals("private-test-user", source.getUsername());
        assertTrue(source.getJdbcUrl().contains("private-url-secret"));
    }
}
