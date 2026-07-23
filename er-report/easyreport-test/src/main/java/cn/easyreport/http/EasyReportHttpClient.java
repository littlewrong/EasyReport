package cn.easyreport.http;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.junit.platform.commons.util.ExceptionUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * @author ChainStrong
 */
@Slf4j
@Component
public class EasyReportHttpClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType STRING = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
    @Resource
    private OkHttpClient okHttpClient;

    /**
     * get 请求
     *
     * @param url 请求url地址
     * @return string
     */
    public String doGet(String url) {
        return doGet(url, null, null);
    }


    /**
     * get 请求
     *
     * @param url    请求url地址
     * @param params 请求参数 map
     * @return string
     */
    public String doGet(String url, Map<String, String> params) {
        return doGet(url, params, null);
    }

    /**
     * get 请求
     *
     * @param url     请求url地址
     * @param params  请求参数 map
     * @param headers 请求头参数 map
     * @return string
     */
    public String doGet(String url, Map<String, String> params, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder(url);
        if (params != null && params.keySet().size() > 0) {
            sb.append("?");
            params.keySet().forEach(key -> {
                String value = params.get(key);
                if (null == value) {
                    value = "";
                }
                sb.append(key).append("=").append(value).append("&");
            });
            sb.deleteCharAt(sb.lastIndexOf("&"));
        }
        Request.Builder builder = new Request.Builder();
        if (headers != null && headers.keySet().size() > 0) {
            headers.keySet().forEach(key -> {
                String value = headers.get(key);
                if (null == value) {
                    value = "";
                }
                builder.addHeader(key, value);
            });
        }
        Request request = builder.url(sb.toString()).build();
        return execute(request);
    }

    /**
     * post 请求
     *
     * @param url    请求url地址
     * @param params 请求参数 map
     * @return string
     */
    public String doPost(String url, Map<String, String> params) {
        FormBody.Builder builder = new FormBody.Builder();
        if (params != null && params.keySet().size() > 0) {
            params.keySet().forEach(key -> {
                String value = params.get(key);
                if (null == value) {
                    value = "";
                }
                builder.add(key, value);
            });
        }
        Request request = new Request.Builder().url(url).post(builder.build()).build();
        return execute(request);
    }

    /**
     * 发送Post 携带header
     *
     * @param url
     * @param jsonStr
     * @param header
     * @return
     */
    public String doPostJson(String url, String jsonStr, Map<String, String> header) {
        Request.Builder builder = new Request.Builder().url(url);
        //循环插入header
        if (null != header && header.size() > 0) {
            header.keySet().forEach(key -> {
                String value = header.get(key);
                if (null == value) {
                    value = "";
                }
                builder.addHeader(key, value);
            });
        }
        RequestBody requestBody = RequestBody.create(jsonStr, JSON);
        Request request = builder.post(requestBody).build();
        return execute(request);
    }


    /**
     * post 请求, 请求数据为 json 的字符串
     *
     * @param url  请求url地址
     * @param json 请求数据, json 字符串
     * @return string
     */
    public String doPostJson(String url, String json) {
        return exectePost(url, json, JSON);
    }

    /**
     * post 请求, 请求数据为 xml 的字符串
     *
     * @param url 请求url地址
     * @param xml 请求数据, xml 字符串
     * @return string
     */
    public String doPostXml(String url, String xml) {
        return exectePost(url, xml, XML);
    }

    /**
     * post 请求, 请求数据为 xml 的字符串
     *
     * @param url  请求url地址
     * @param data 请求数据   表单数据
     * @return string
     */
    public String doPostXmlFormString(String url, Map<String, String> data) {
        FormBody.Builder builder = new FormBody.Builder();
        if (data != null && data.keySet().size() > 0) {
            data.keySet().forEach(key -> {
                String value = data.get(key);
                if (null == value) {
                    value = "";
                }
                builder.add(key, value);
            });
        }
        Request request = new Request.Builder().url(url).post(builder.build()).build();
        return execute(request);
    }


    private String exectePost(String url, String data, MediaType contentType) {
        RequestBody requestBody = RequestBody.create(data, contentType);
        Request request = new Request.Builder().url(url).post(requestBody).build();
        return execute(request);
    }

    public String execute(Request request) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    return responseBody.string();
                }
            }
        } catch (Exception e) {
            log.error(ExceptionUtils.readStackTrace(e));
        }
        return "";
    }
}
