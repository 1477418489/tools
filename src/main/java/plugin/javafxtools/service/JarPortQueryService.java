package plugin.javafxtools.service;

import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
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

    /**
     * 创建端口查询服务。
     *
     * @param backgroundExecutor 后台执行器
     * @param portProcessService 端口进程服务
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     * @param confirmKillProcessOnPort 终止端口占用进程确认回调
     */
    public JarPortQueryService(ExecutorService backgroundExecutor,
                               JarPortProcessService portProcessService,
                               Consumer<String> logger,
                               Consumer<String> errorReporter,
                               IntPredicate confirmKillProcessOnPort) {
        this.backgroundExecutor = backgroundExecutor;
        this.portProcessService = portProcessService;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.confirmKillProcessOnPort = confirmKillProcessOnPort;
    }

    /**
     * 查询端口占用情况，必要时提示用户终止占用进程。
     *
     * @param portText 端口文本
     */
    public void queryPort(String portText) {
        String port = portText == null ? "" : portText.trim();
        if (port.isEmpty()) {
            errorReporter.accept("端口为空,请输入端口");
            return;
        }

        int targetPort;
        try {
            targetPort = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            errorReporter.accept("端口必须是数字");
            return;
        }

        backgroundExecutor.submit(() -> {
            boolean inUse = portProcessService.checkPortInUse(targetPort);
            if (!inUse) {
                Platform.runLater(() -> logger.accept("端口:" + targetPort + " 未被占用"));
                return;
            }

            String processInfo = portProcessService.getProcessUsingPort(targetPort);
            Platform.runLater(() -> {
                logger.accept("端口:" + targetPort + " 被占用，" + processInfo);
                if (confirmKillProcessOnPort.test(targetPort)) {
                    backgroundExecutor.submit(() -> portProcessService.killProcessOnPort(targetPort));
                }
            });
        });
    }
}
