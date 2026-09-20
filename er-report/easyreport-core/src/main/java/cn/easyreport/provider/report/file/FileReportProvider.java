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
package cn.easyreport.provider.report.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;

import cn.easyreport.exception.ReportFileException;
import cn.easyreport.provider.report.ReportFile;
import cn.easyreport.provider.report.ReportProvider;

/**
 * @author Jacky.gao
 * @since 2017年2月11日
 */
public class FileReportProvider implements ReportProvider,ApplicationContextAware{
	private static final Logger LOGGER=Logger.getLogger(FileReportProvider.class.getName());
	private String prefix="file:";
	private String fileStoreDir;
	private boolean disabled;
	@Override
	public InputStream loadReport(String file) {
		try {
			Path path=resolveReportPath(file, true);
			return Channels.newInputStream(Files.newByteChannel(path,
					StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
		} catch (IOException | SecurityException e) {
			throw fileAccessException(e);
		}
	}
	
	@Override
	public void deleteReport(String file) {
		try {
			Files.deleteIfExists(resolveReportPath(file, false));
		} catch (IOException | SecurityException e) {
			throw fileAccessException(e);
		}
	}

	@Override
	public List<ReportFile> getReportFiles() {
		List<ReportFile> list=new ArrayList<ReportFile>();
		try (DirectoryStream<Path> files=Files.newDirectoryStream(reportDirectory())) {
			for(Path file:files){
				if(!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)){
					continue;
				}
				Path report;
				try {
					report=resolveReportPath(file.getFileName().toString(), true);
				} catch (ReportFileException e) {
					// Only advertise files that the same provider can safely open.
					continue;
				}
				Calendar calendar=Calendar.getInstance();
				calendar.setTimeInMillis(Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toMillis());
				list.add(new ReportFile(file.getFileName().toString(),calendar.getTime()));
			}
		} catch (DirectoryIteratorException e) {
			throw fileAccessException(e.getCause());
		} catch (IOException | SecurityException e) {
			throw fileAccessException(e);
		}
		Collections.sort(list, new Comparator<ReportFile>(){
			@Override
			public int compare(ReportFile f1, ReportFile f2) {
				return f2.getUpdateDate().compareTo(f1.getUpdateDate());
			}
		});
		return list;
	}

	@Override
	public String getName() {
		return "服务器文件系统";
	}
	
	@Override
	public void saveReport(String file,String content) {
		try {
			Path path=resolveReportPath(file, false);
			try (OutputStream out=Channels.newOutputStream(Files.newByteChannel(path,
					StandardOpenOption.WRITE, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))) {
				IOUtils.write(content, out,"utf-8");
			}
		} catch (IOException | SecurityException e) {
			throw fileAccessException(e);
		}
	}

	/**
	 * Resolve the final decoded report name once for all read/write/delete callers.
	 * The configured storage directory is trusted; links below it are not accepted.
	 */
	private Path resolveReportPath(String file, boolean mustExist) throws IOException {
		if(file==null || file.length()==0){
			throw invalidPath();
		}
		if(file.startsWith(prefix)){
			file=file.substring(prefix.length());
		}
		String name=file.replace('\\', '/');
		if(name.startsWith("/") || !name.toLowerCase(Locale.ROOT).endsWith(".xml")){
			throw invalidPath();
		}
		for(int i=0;i<name.length();i++){
			char ch=name.charAt(i);
			if(Character.isISOControl(ch) || ":*?\"<>|".indexOf(ch)>=0){
				throw invalidPath();
			}
		}
		for(String part:name.split("/", -1)){
			if(part.length()==0 || part.equals(".") || part.equals("..")
					|| part.endsWith(".") || part.endsWith(" ")){
				throw invalidPath();
			}
			// Windows device names remain special even when followed by .xml.
			String base=part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
			if(base.matches("CON|PRN|AUX|NUL|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3]")){
				throw invalidPath();
			}
		}
		Path relative;
		try {
			relative=Paths.get(name);
		} catch (InvalidPathException e) {
			throw invalidPath();
		}
		Path root=reportDirectory();
		Path target=root.resolve(relative).normalize();
		if(relative.isAbsolute() || !target.startsWith(root) || target.equals(root)){
			throw invalidPath();
		}
		Path current=root;
		for(Path part:relative){
			current=current.resolve(part);
			if(Files.exists(current, LinkOption.NOFOLLOW_LINKS)){
				BasicFileAttributes attributes=Files.readAttributes(current,
						BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				// Also reject Windows junctions and other special filesystem entries.
				if(attributes.isSymbolicLink() || attributes.isOther()
						|| !current.toRealPath().startsWith(root)){
					throw invalidPath();
				}
			}
		}
		// Resolve the existing parent too, including when creating a new report.
		Path parent=target.getParent().toRealPath();
		if(!parent.startsWith(root)){
			throw invalidPath();
		}
		target=parent.resolve(target.getFileName());
		if(Files.exists(target, LinkOption.NOFOLLOW_LINKS)){
			Path realTarget=target.toRealPath();
			if(!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
					|| !realTarget.startsWith(root)
					|| !realTarget.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")){
				throw invalidPath();
			}
			target=realTarget;
		}else if(mustExist){
			throw new ReportFileException(404, "Report file not found.");
		}
		return target;
	}

	private Path reportDirectory() throws IOException {
		if(fileStoreDir==null || fileStoreDir.length()==0){
			throw new IOException("Report storage directory is not configured.");
		}
		try {
			Path root=Paths.get(fileStoreDir).toRealPath();
			if(!Files.isDirectory(root)){
				throw new IOException("Report storage directory is not a directory.");
			}
			return root;
		} catch (InvalidPathException e) {
			throw new IOException("Report storage directory is invalid.", e);
		} catch (IOException e) {
			throw new IOException("Report storage directory is unavailable.", e);
		}
	}

	private ReportFileException invalidPath() {
		return new ReportFileException(400, "Invalid report file path.");
	}

	private ReportFileException fileAccessException(Exception e) {
		LOGGER.log(Level.WARNING, "Unable to access report storage.", e);
		if(e instanceof NoSuchFileException){
			return new ReportFileException(404, "Report file not found.");
		}
		return new ReportFileException(500, "Unable to access report file.");
	}

	@Override
	public boolean disabled() {
		return disabled;
	}
	
	public void setDisabled(boolean disabled) {
		this.disabled = disabled;
	}
	
	public void setFileStoreDir(String fileStoreDir) {
		this.fileStoreDir = fileStoreDir;
	}
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		File file=new File(fileStoreDir);
		if(file.exists()){
			return;
		}
		if(applicationContext instanceof WebApplicationContext){
			WebApplicationContext context=(WebApplicationContext)applicationContext;
			ServletContext servletContext=context.getServletContext();
			String basePath=servletContext.getRealPath("/");
			fileStoreDir=basePath+fileStoreDir;
			file=new File(fileStoreDir);
			if(!file.exists()){
				file.mkdirs();
			}
		}
	}

	@Override
	public String getPrefix() {
		return prefix;
	}
}
