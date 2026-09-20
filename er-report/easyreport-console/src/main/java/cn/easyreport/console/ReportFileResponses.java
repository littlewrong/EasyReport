package cn.easyreport.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import cn.easyreport.exception.ReportFileException;

/** Writes safe file errors even when servlet/reflection layers wrap them. */
public final class ReportFileResponses {
    private ReportFileResponses() {
    }

    public static boolean writeIfFileError(HttpServletResponse response, Throwable error) throws IOException {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (error != null && visited.add(error)) {
            if (error instanceof ReportFileException) {
                ReportFileException fileError = (ReportFileException) error;
                if (!response.isCommitted()) {
                    response.resetBuffer();
                    response.setStatus(fileError.getStatusCode());
                    response.setContentType("text/plain");
                    response.setCharacterEncoding("UTF-8");
                    try {
                        response.getWriter().write(fileError.getMessage());
                    } catch (IllegalStateException streamAlreadySelected) {
                        response.getOutputStream().write(fileError.getMessage().getBytes(StandardCharsets.UTF_8));
                    }
                }
                return true;
            }
            error = error.getCause();
        }
        return false;
    }
}
