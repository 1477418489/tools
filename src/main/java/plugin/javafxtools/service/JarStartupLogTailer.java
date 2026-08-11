package plugin.javafxtools.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 增量读取后台 Java 进程写入的启动日志，不持有子进程输出管道。
 */
public final class JarStartupLogTailer {
    private final LogFileTailer delegate;

    public JarStartupLogTailer(Path logFile, long startOffset) {
        this.delegate = new LogFileTailer(Objects.requireNonNull(logFile, "logFile"), startOffset);
    }

    /**
     * 读取从上次调用后新增的完整日志行。
     *
     * @param flushPartial 是否把尚未换行的末尾内容作为最后一行返回
     * @return 新增日志行
     * @throws IOException 日志读取失败
     */
    public synchronized List<String> readAvailable(boolean flushPartial) throws IOException {
        return delegate.readAvailable(flushPartial);
    }
}
