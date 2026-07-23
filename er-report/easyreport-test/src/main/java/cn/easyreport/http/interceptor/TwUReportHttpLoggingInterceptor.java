package cn.easyreport.http.interceptor;

import lombok.extern.slf4j.Slf4j;
import okhttp3.logging.HttpLoggingInterceptor;
import org.jetbrains.annotations.NotNull;

/**
 * @author ChainStrong
 */
@Slf4j
public class TwUReportHttpLoggingInterceptor implements HttpLoggingInterceptor.Logger {

    @Override
    public void log(@NotNull String message) {
        log.info(message);
    }
}
