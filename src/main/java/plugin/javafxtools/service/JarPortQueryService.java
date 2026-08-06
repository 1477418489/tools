package plugin.javafxtools.service;

import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * JAR 启动器端口占用查询操作。
 *
 * @author wwj
 */
public class JarPortQueryService {
    private final ExecutorService backgroundExecutor;
    private final JarPortProcessService portProcessService;
    private final Consumer<String> logger;
    private final Consumer<String> errorReporter;
    private final IntPredicate confirmKillProcessOnPort;
    private final Runnable operationFinished;

    /**
     * 创建端口查询服务。
     *
     * @param backgroundExecutor 后台执行器
     * @param portProcessService 端口进程服务
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     * @param confirmKillProcessOnPort 终止端口占用进程确认回调
     * @param operationFinished 查询完成回调
     */
    public JarPortQueryService(ExecutorService backgroundExecutor,
                               JarPortProcessService portProcessService,
                               Consumer<String> logger,
                               Consumer<String> errorReporter,
                               IntPredicate confirmKillProcessOnPort,
                               Runnable operationFinished) {
        this.backgroundExecutor = backgroundExecutor;
        this.portProcessService = portProcessService;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.confirmKillProcessOnPort = confirmKillProcessOnPort;
        this.operationFinished = operationFinished;
    }

    /**
     * 查询端口占用情况，必要时提示用户终止占用进程。
     *
     * @param targetPort 目标端口
     */
    public void queryPort(int targetPort) {
        try {
            backgroundExecutor.submit(() -> {
                try {
                    boolean inUse = portProcessService.checkPortInUse(targetPort);
                    if (!inUse) {
                        finish(() -> logger.accept("端口:" + targetPort + " 未被占用"));
                        return;
                    }

                    String processInfo = portProcessService.getProcessUsingPort(targetPort);
                    Platform.runLater(() -> confirmTermination(targetPort, processInfo));
                } catch (RuntimeException e) {
                    finish(() -> errorReporter.accept("查询端口失败: " + errorMessage(e)));
                }
            });
        } catch (RejectedExecutionException e) {
            finish(() -> errorReporter.accept("端口查询服务已关闭"));
        }
    }

    private void confirmTermination(int targetPort, String processInfo) {
        logger.accept("端口:" + targetPort + " 被占用，" + processInfo);
        try {
            if (!confirmKillProcessOnPort.test(targetPort)) {
                operationFinished.run();
                return;
            }
            backgroundExecutor.submit(() -> {
                try {
                    portProcessService.killProcessOnPort(targetPort);
                    finish(() -> { });
                } catch (RuntimeException e) {
                    finish(() -> errorReporter.accept("终止端口进程失败: " + errorMessage(e)));
                }
            });
        } catch (RejectedExecutionException e) {
            finish(() -> errorReporter.accept("端口查询服务已关闭"));
        } catch (RuntimeException e) {
            finish(() -> errorReporter.accept("端口操作失败: " + errorMessage(e)));
        }
    }

    private void finish(Runnable uiAction) {
        Runnable finishAction = () -> {
            try {
                uiAction.run();
            } finally {
                operationFinished.run();
            }
        };
        if (Platform.isFxApplicationThread()) {
            finishAction.run();
        } else {
            Platform.runLater(finishAction);
        }
    }

    private String errorMessage(RuntimeException exception) {
        return exception.getMessage() != null ? exception.getMessage() : "未知错误";
    }
}
