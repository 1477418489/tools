package plugin.javafxtools.model;


/**
 * JAR 启动器中的项目启动配置。
 */
public class ProjectConfig {
    /**
     * 项目配置唯一标识。
     */
    public int id;

    /**
     * 项目名称。
     */
    public String name;

    /**
     * 源 JAR 文件路径。
     */
    public String sourceJar;

    /**
     * 目标 JAR 文件路径。
     */
    public String targetJar;

    /**
     * 源 lib 目录路径。
     */
    public String sourceLib;

    /**
     * 目标 lib 目录路径。
     */
    public String libTarget;

    /**
     * 默认启动端口。
     */
    public int defaultPort;

    /**
     * 默认 Spring profile。
     */
    public String defaultProfile;

    /**
     * JVM 启动参数。
     */
    public String jvmOpts;

    /**
     * 其他应用启动参数。
     */
    public String otherOpts;

    /**
     * 供 JSON 反序列化使用的无参构造方法。
     */
    public ProjectConfig() {
    }

    /**
     * 复制项目配置。
     *
     * @param other 原始项目配置
     */
    public ProjectConfig(ProjectConfig other) {
        this.id = other.id;
        this.name = other.name;
        this.sourceJar = other.sourceJar;
        this.targetJar = other.targetJar;
        this.sourceLib = other.sourceLib;
        this.libTarget = other.libTarget;
        this.defaultPort = other.defaultPort;
        this.defaultProfile = other.defaultProfile;
        this.jvmOpts = other.jvmOpts;
        this.otherOpts = other.otherOpts;
    }

    /**
     * 获取项目配置唯一标识。
     *
     * @return 项目配置唯一标识
     */
    public int getId() {
        return id;
    }

    /**
     * 设置项目配置唯一标识。
     *
     * @param id 项目配置唯一标识
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * 获取项目名称。
     *
     * @return 项目名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置项目名称。
     *
     * @param name 项目名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取源 JAR 文件路径。
     *
     * @return 源 JAR 文件路径
     */
    public String getSourceJar() {
        return sourceJar;
    }

    /**
     * 设置源 JAR 文件路径。
     *
     * @param sourceJar 源 JAR 文件路径
     */
    public void setSourceJar(String sourceJar) {
        this.sourceJar = sourceJar;
    }

    /**
     * 获取目标 JAR 文件路径。
     *
     * @return 目标 JAR 文件路径
     */
    public String getTargetJar() {
        return targetJar;
    }

    /**
     * 设置目标 JAR 文件路径。
     *
     * @param targetJar 目标 JAR 文件路径
     */
    public void setTargetJar(String targetJar) {
        this.targetJar = targetJar;
    }

    /**
     * 获取源 lib 目录路径。
     *
     * @return 源 lib 目录路径
     */
    public String getSourceLib() {
        return sourceLib;
    }

    /**
     * 设置源 lib 目录路径。
     *
     * @param sourceLib 源 lib 目录路径
     */
    public void setSourceLib(String sourceLib) {
        this.sourceLib = sourceLib;
    }

    /**
     * 获取目标 lib 目录路径。
     *
     * @return 目标 lib 目录路径
     */
    public String getLibTarget() {
        return libTarget;
    }

    /**
     * 设置目标 lib 目录路径。
     *
     * @param libTarget 目标 lib 目录路径
     */
    public void setLibTarget(String libTarget) {
        this.libTarget = libTarget;
    }

    /**
     * 获取默认启动端口。
     *
     * @return 默认启动端口
     */
    public int getDefaultPort() {
        return defaultPort;
    }

    /**
     * 设置默认启动端口。
     *
     * @param defaultPort 默认启动端口
     */
    public void setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    /**
     * 获取默认 Spring profile。
     *
     * @return 默认 Spring profile
     */
    public String getDefaultProfile() {
        return defaultProfile;
    }

    /**
     * 设置默认 Spring profile。
     *
     * @param defaultProfile 默认 Spring profile
     */
    public void setDefaultProfile(String defaultProfile) {
        this.defaultProfile = defaultProfile;
    }

    /**
     * 获取 JVM 启动参数。
     *
     * @return JVM 启动参数
     */
    public String getJvmOpts() {
        return jvmOpts;
    }

    /**
     * 设置 JVM 启动参数。
     *
     * @param jvmOpts JVM 启动参数
     */
    public void setJvmOpts(String jvmOpts) {
        this.jvmOpts = jvmOpts;
    }

    /**
     * 获取其他应用启动参数。
     *
     * @return 其他应用启动参数
     */
    public String getOtherOpts() {
        return otherOpts;
    }

    /**
     * 设置其他应用启动参数。
     *
     * @param otherOpts 其他应用启动参数
     */
    public void setOtherOpts(String otherOpts) {
        this.otherOpts = otherOpts;
    }

    /**
     * 返回用于 ComboBox 展示的项目名称。
     *
     * @return 项目名称
     */
    @Override
    public String toString() {
        return name;
    }
}
