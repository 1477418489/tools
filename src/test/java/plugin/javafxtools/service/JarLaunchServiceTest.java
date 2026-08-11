package plugin.javafxtools.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.model.ProjectConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarLaunchServiceTest {
    @TempDir
    Path tempDirectory;

    private final ExecutorService launchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService monitorExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().factory());
    private final List<StubProcess> processes = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        processes.forEach(Process::destroyForcibly);
        launchExecutor.shutdownNow();
        monitorExecutor.shutdownNow();
    }

    @Test
    void startupMonitoringDoesNotBlockLaunchingAnotherProject() throws Exception {
        CountDownLatch processStarts = new CountDownLatch(2);
        CountDownLatch startupLogs = new CountDownLatch(2);
        List<String> logs = new CopyOnWriteArrayList<>();
        JarFileService fileService = new StubJarFileService(processStarts);
        JarPortProcessService portService = new AlwaysFreePortService();
        JarLaunchService service = new JarLaunchService(
                launchExecutor,
                monitorExecutor,
                portService,
                fileService,
                message -> {
                    logs.add(message);
                    if (message.contains("application booting")) {
                        startupLogs.countDown();
                    }
                },
                logs::add,
                _ -> false,
                (_, _) -> { },
                (_, _) -> { },
                (_, _) -> { },
                _ -> { },
                Runnable::run);

        service.launch(project(1), 18081, "dev");
        service.launch(project(2), 18082, "dev");

        assertTrue(processStarts.await(1, TimeUnit.SECONDS),
                "第二个应用不应等待第一个应用端口就绪");
        assertTrue(startupLogs.await(2, TimeUnit.SECONDS),
                "后台启动日志应持续转发到启动器");
        assertTrue(logs.stream().anyMatch(line -> line.contains("[project-1] application booting")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("[project-2] application booting")));
    }

    @Test
    void laterProcessExitDoesNotFinishAnotherProjectOperation() throws Exception {
        CountDownLatch processStart = new CountDownLatch(1);
        CountDownLatch startupFinished = new CountDownLatch(1);
        CountDownLatch processStateChanged = new CountDownLatch(1);
        AtomicInteger operationFinishCount = new AtomicInteger();
        JarLaunchService service = new JarLaunchService(
                launchExecutor,
                monitorExecutor,
                new BecomesReadyPortService(),
                new StubJarFileService(processStart),
                _ -> { },
                _ -> { },
                _ -> false,
                (_, _) -> {
                    operationFinishCount.incrementAndGet();
                    startupFinished.countDown();
                },
                (_, _) -> processStateChanged.countDown(),
                (_, _) -> { },
                _ -> { },
                Runnable::run);

        service.launch(project(3), 18083, "dev");

        assertTrue(processStart.await(1, TimeUnit.SECONDS));
        assertTrue(startupFinished.await(2, TimeUnit.SECONDS));
        processes.getFirst().destroy();
        assertTrue(processStateChanged.await(1, TimeUnit.SECONDS));
        assertEquals(1, operationFinishCount.get(),
                "进程后续退出只能刷新状态，不能完成新的项目操作");
    }

    @Test
    void continuesStreamingLogsAfterPortBecomesReady() throws Exception {
        CountDownLatch processStart = new CountDownLatch(1);
        CountDownLatch startupFinished = new CountDownLatch(1);
        CountDownLatch runtimeLog = new CountDownLatch(1);
        List<String> logs = new CopyOnWriteArrayList<>();
        JarLaunchService service = new JarLaunchService(
                launchExecutor,
                monitorExecutor,
                new BecomesReadyPortService(),
                new StubJarFileService(processStart),
                message -> {
                    logs.add(message);
                    if (message.contains("runtime log")) {
                        runtimeLog.countDown();
                    }
                },
                logs::add,
                _ -> false,
                (_, _) -> startupFinished.countDown(),
                (_, _) -> { },
                (_, _) -> { },
                _ -> { },
                Runnable::run);

        service.launch(project(4), 18084, "dev");

        assertTrue(processStart.await(1, TimeUnit.SECONDS));
        assertTrue(startupFinished.await(2, TimeUnit.SECONDS));
        Files.writeString(tempDirectory.resolve("startup-4.log"), "runtime log\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertTrue(runtimeLog.await(2, TimeUnit.SECONDS),
                "端口就绪后仍应持续转发运行日志");
    }

    private ProjectConfig project(int id) {
        ProjectConfig project = new ProjectConfig();
        project.setId(id);
        project.setName("project-" + id);
        project.setTargetJar(tempDirectory.resolve("project-" + id + ".jar").toString());
        return project;
    }

    private final class StubJarFileService extends JarFileService {
        private final CountDownLatch processStarts;

        private StubJarFileService(CountDownLatch processStarts) {
            super(_ -> { });
            this.processStarts = processStarts;
        }

        @Override
        public JavaLaunch startJavaApplication(ProjectConfig project, int port, String profile)
                throws IOException {
            Path outputLog = tempDirectory.resolve("startup-" + project.getId() + ".log");
            Files.writeString(outputLog, "application booting\n", StandardCharsets.UTF_8);
            StubProcess process = new StubProcess(10_000L + project.getId());
            processes.add(process);
            processStarts.countDown();
            return new JavaLaunch(process, outputLog, 0);
        }
    }

    private static final class AlwaysFreePortService extends JarPortProcessService {
        private AlwaysFreePortService() {
            super(_ -> { }, _ -> { });
        }

        @Override
        public ProjectPortInspection inspectProjectPort(ProjectConfig project, int port) {
            return new ProjectPortInspection(ProjectPortState.FREE, Set.of(), Set.of());
        }

        @Override
        public boolean checkPortInUse(int port) {
            return false;
        }
    }

    private static final class BecomesReadyPortService extends JarPortProcessService {
        private final AtomicInteger inspections = new AtomicInteger();

        private BecomesReadyPortService() {
            super(_ -> { }, _ -> { });
        }

        @Override
        public ProjectPortInspection inspectProjectPort(ProjectConfig project, int port) {
            return inspections.getAndIncrement() == 0
                    ? new ProjectPortInspection(ProjectPortState.FREE, Set.of(), Set.of())
                    : new ProjectPortInspection(ProjectPortState.PROJECT_RUNNING,
                    Set.of("10003"), Set.of());
        }

        @Override
        public boolean checkPortInUse(int port) {
            return true;
        }
    }

    private static final class StubProcess extends Process {
        private final long pid;
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private volatile boolean alive = true;

        private StubProcess(long pid) {
            this.pid = pid;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.thenApply(_ -> 0).get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
            exit.complete(this);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }
    }
}
