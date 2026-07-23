package cn.easyreport.config;

import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.provider.image.ImageProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * guohongjian
 */
@Component
public class PictureUpload implements ImageProvider, ApplicationContextAware {
    @Value("${tw.profile}")
    private String profile;
    private ApplicationContext applicationContext;
    private String baseWebPath;
    @Override
    public InputStream getImage(String path) {
        System.out.println(path);
        try {
            if( path.startsWith(profile)){
                //返回一个输入流，该输入流的读取位置是传入进来的path，也就是界面上输入的地址
                File file=new File(path);
                InputStream inputStream=new FileInputStream(file);
                return inputStream;
            }else{
                path=baseWebPath+path;
                return new FileInputStream(path);
            }
        } catch (IOException e) {
            throw new ReportComputeException(e);
        }
    }
    @Override
    public boolean support(String path) {
        if(path.startsWith(profile)){
            return true;
        }else if(baseWebPath!=null && path.startsWith(profile)){
            return true;
        }
        return false;
    }
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if(applicationContext instanceof WebApplicationContext){
            WebApplicationContext context=(WebApplicationContext)applicationContext;
            baseWebPath=context.getServletContext().getRealPath(profile);
        }
        this.applicationContext=applicationContext;
    }
}
