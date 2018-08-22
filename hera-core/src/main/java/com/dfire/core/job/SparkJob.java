package com.dfire.core.job;

import com.dfire.common.constants.RunningJobKeyConstant;
import com.dfire.common.service.HeraFileService;
import com.dfire.core.config.HeraGlobalEnvironment;
import com.dfire.core.tool.ConnectionTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description ： SparkJob
 * @Author ： HeGuoZi
 * @Date ： 15:53 2018/8/20
 * @Modified :
 */
@Slf4j
public class SparkJob extends ProcessJob {

    public final String UDF_SQL_NAME = "hera_udf.sql";

    private HeraFileService    heraFileService;
    private ApplicationContext applicationContext;

    public SparkJob(JobContext jobContext, ApplicationContext applicationContext) {
        super(jobContext);
        this.applicationContext = applicationContext;
        this.heraFileService = (HeraFileService) this.applicationContext.getBean("heraFileService");
        jobContext.getProperties().setProperty(RunningJobKeyConstant.JOB_RUN_TYPE, "SparkJob");
    }

    @Override
    public int run() throws Exception {
        Integer exitCode = runInner();
        return exitCode;
    }

    private Integer runInner() throws Exception {
        String script = getProperties().getLocalProperty(RunningJobKeyConstant.JOB_SCRIPT);
        File file = new File(jobContext.getWorkDir() + File.separator + System.currentTimeMillis() + ".spark");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                log.error("创建.spark失败");
            }
        }

        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file),
                    Charset.forName(jobContext.getProperties().getProperty("hera.fs.encode", "utf-8")));
            writer.write(script.replaceAll("^--.*", "--"));

        } catch (Exception e) {
            jobContext.getHeraJobHistory().getLog().appendHeraException(e);
        } finally {
            IOUtils.closeQuietly(writer);
        }

        getProperties().setProperty(RunningJobKeyConstant.RUN_SPARK_PATH, file.getAbsolutePath());
        return super.run();
    }

    public static void executeJob(String context) {
        Statement stmt = ConnectionTool.getConnection();
        int last = 0;
        for (int now = 0; now < context.length(); now++) {
            if(";".equals(context.substring(now))){
                try {
                    ResultSet resultSet = stmt.executeQuery(context.substring(last, now));
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public List<String> getCommandList() {
        String sparkFilePath = getProperty(RunningJobKeyConstant.RUN_SPARK_PATH, "");
        List<String> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        String shellPrefix = "";
        String user = "";
        if (jobContext.getRunType() == 1 || jobContext.getRunType() == 2) {
            user = jobContext.getHeraJobHistory().getOperator();
            shellPrefix = "sudo -u " + user;
        } else if (jobContext.getRunType() == 3) {
            user = jobContext.getDebugHistory().getOwner();
            shellPrefix = "sudo -u " + user;
        } else if (jobContext.getRunType() == 4) {
            shellPrefix = "";
        } else {
            log.info("没有运行类型 runType = " + jobContext.getRunType());
        }

        String[] excludeFile = HeraGlobalEnvironment.excludeFile.split(";");
        boolean isDocToUnix = true;
        if (!ArrayUtils.isEmpty(excludeFile)) {
            String lowCaseShellPath = sparkFilePath.toLowerCase();
            for (String exclude : excludeFile) {
                if (lowCaseShellPath.endsWith("." + exclude)) {
                    isDocToUnix = false;
                    break;
                }
            }
        }

        if (isDocToUnix) {
            list.add("dos2unix " + sparkFilePath);
            log("dos2unix file" + sparkFilePath);
        }

        sb.append(" -f " + sparkFilePath + " " + HeraGlobalEnvironment.getSparkConfig());

        if (shellPrefix.trim().length() > 0) {

            String tmpFilePath = jobContext.getWorkDir() + File.separator + "tmp.sh";
            File tmpFile = new File(tmpFilePath);
            OutputStreamWriter tmpWriter = null;
            if (!tmpFile.exists()) {
                try {
                    tmpFile.createNewFile();
                    tmpWriter = new OutputStreamWriter(new FileOutputStream(tmpFile),
                            Charset.forName(jobContext.getProperties().getProperty("hera.fs.encode", "utf-8")));
                    tmpWriter.write("/opt/app/spark231/bin/spark-sql " + sb.toString());
                } catch (Exception e) {
                    jobContext.getHeraJobHistory().getLog().appendHeraException(e);
                } finally {
                    IOUtils.closeQuietly(tmpWriter);
                }
                list.add("chmod -R 777 " + jobContext.getWorkDir());
                list.add(shellPrefix + " sh " + tmpFilePath);
            } else {
                list.add("chmod -R 777 " + jobContext.getWorkDir());
                list.add(shellPrefix + " /opt/app/spark231/bin/spark-sql " + sb.toString());
            }

        } else {
            list.add("/opt/app/spark231/bin/spark-sql " + sb.toString());
        }
        return list;
    }
}
