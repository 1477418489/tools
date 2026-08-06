package plugin.javafxtools.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 应用运行数据目录解析。
 */
public final class AppDataPaths {
    private static final String APP_DIRECTORY_NAME = "FxTools";
    private static final Path DATA_DIRECTORY = resolveDataDirectory(
            System.getProperty("os.name"),
            System.getenv("LOCALAPPDATA"),
            System.getProperty("user.home")
    );

    private AppDataPaths() {
    }

    /**
     * 获取应用数据目录。
     *
     * @return 绝对且规范化的数据目录
     */
    public static Path dataDirectory() {
        return DATA_DIRECTORY;
    }

    /**
     * 获取数据目录内的文件路径。
     *
     * @param fileName 不包含目录部分的文件名
     * @return 数据文件路径
     */
    public static Path dataFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("数据文件名不能为空");
        }
        Path relativePath = Path.of(fileName);
        if (relativePath.isAbsolute() || relativePath.getNameCount() != 1
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("数据文件名不能包含目录: " + fileName);
        }
        return DATA_DIRECTORY.resolve(relativePath);
    }

    /**
     * 确保应用数据目录存在。
     *
     * @throws IOException 目录创建失败
     */
    public static void ensureDataDirectory() throws IOException {
        Files.createDirectories(DATA_DIRECTORY);
    }

    static Path resolveDataDirectory(String osName, String localAppData, String userHome) {
        boolean windows = osName != null
                && osName.toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, APP_DIRECTORY_NAME).toAbsolutePath().normalize();
        }
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("无法确定用户数据目录");
        }

        Path home = Path.of(userHome);
        Path directory = windows
                ? home.resolve("AppData").resolve("Local").resolve(APP_DIRECTORY_NAME)
                : home.resolve(".fxtools");
        return directory.toAbsolutePath().normalize();
    }
}
