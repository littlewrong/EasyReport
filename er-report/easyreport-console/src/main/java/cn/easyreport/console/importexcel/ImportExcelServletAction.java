/*******************************************************************************
 * Copyright 2017 Bstek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package cn.easyreport.console.importexcel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import cn.easyreport.console.RenderPageServletAction;
import cn.easyreport.console.cache.TempObjectCache;
import cn.easyreport.definition.ReportDefinition;

public class ImportExcelServletAction extends RenderPageServletAction {
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final long MAX_REQUEST_BYTES = 20L * 1024 * 1024;
    private static final Logger LOGGER = Logger.getLogger(ImportExcelServletAction.class.getName());

    @Override
    public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            resp.setHeader("Allow", "POST");
            respond(resp, 405, false);
            return;
        }
        if (!ServletFileUpload.isMultipartContent(req)) {
            respond(resp, 400, false);
            return;
        }
        Path directory = null;
        Path uploadFile = null;
        ReportDefinition report = null;
        try (ServletInputStream requestStream = req.getInputStream()) {
            ServletFileUpload upload = new ServletFileUpload();
            upload.setHeaderEncoding("UTF-8");
            upload.setSizeMax(MAX_REQUEST_BYTES);
            upload.setFileSizeMax(MAX_FILE_BYTES);
            FileItemIterator items = upload.getItemIterator(req);
            String name = null;
            int count = 0;
            while (items.hasNext()) {
                FileItemStream item = items.next();
                // Streaming bounds parts before allocating a list of FileItem objects.
                if (++count > 1 || item.isFormField() || !"_excel_file".equals(item.getFieldName())) {
                    throw new IOException("Expected one Excel upload.");
                }
                name = ExcelUploadValidator.validateName(item.getName());
                directory = createUploadDirectory();
                uploadFile = directory.resolve("upload.tmp");
                try (InputStream source = item.openStream(); OutputStream output = Files.newOutputStream(uploadFile,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[8192];
                    long size = 0;
                    int read;
                    while ((read = source.read(buffer)) != -1) {
                        size += read;
                        if (size > MAX_FILE_BYTES) throw new IOException("Upload is too large.");
                        output.write(buffer, 0, read);
                    }
                    if (size == 0) throw new IOException("Upload is empty.");
                }
            }
            if (count != 1) throw new IOException("Expected one Excel upload.");
            ExcelUploadValidator.validate(uploadFile, name);
            ExcelParser parser = name.endsWith(".xlsx") ? new XSSFExcelParser() : new HSSFExcelParser();
            try (InputStream source = Files.newInputStream(uploadFile)) {
                report = parser.parse(source);
            }
        } catch (Exception invalid) {
            // Parser exceptions may contain absolute paths or user-controlled XML.
            LOGGER.log(Level.FINE, "Rejected Excel upload.", invalid);
        } finally {
            boolean fileRemoved = deleteTemporary(uploadFile);
            boolean directoryRemoved = deleteTemporary(directory);
            if (!fileRemoved || !directoryRemoved) report = null;
        }
        if (report == null) {
            respond(resp, 400, false);
            return;
        }
        TempObjectCache.putObject("classpath:template/template.easyreport.xml", report);
        respond(resp, 200, true);
    }

    Path createUploadDirectory() throws IOException {
        // The client filename never participates in any filesystem path.
        return Files.createTempDirectory("easyreport-import-");
    }

    private boolean deleteTemporary(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
            return true;
        } catch (IOException | SecurityException cleanupFailure) {
            LOGGER.log(Level.WARNING, "Unable to remove temporary Excel upload.", cleanupFailure);
            return false;
        }
    }

    private void respond(HttpServletResponse resp, int status, boolean success) throws ServletException, IOException {
        resp.setStatus(status);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("result", success);
        if (!success) result.put("errorInfo", "Invalid Excel upload or import limits exceeded.");
        writeObjectToJson(resp, result);
    }

    @Override
    public String url() { return "/import"; }
}
