package plugin.javafxtools.model;


public class ProjectConfig {
    public int id;
    public String name;
    public String sourceJar;
    public String targetJar;
    public String sourceLib;
    public String libTarget;
    public int defaultPort;
    public String defaultProfile;
    public String jvmOpts;
    public String otherOpts;

    public ProjectConfig() {
    }

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

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceJar() {
        return sourceJar;
    }

    public void setSourceJar(String sourceJar) {
        this.sourceJar = sourceJar;
    }

    public String getTargetJar() {
        return targetJar;
    }

    public void setTargetJar(String targetJar) {
        this.targetJar = targetJar;
    }

    public String getSourceLib() {
        return sourceLib;
    }

    public void setSourceLib(String sourceLib) {
        this.sourceLib = sourceLib;
    }

    public String getLibTarget() {
        return libTarget;
    }

    public void setLibTarget(String libTarget) {
        this.libTarget = libTarget;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public String getDefaultProfile() {
        return defaultProfile;
    }

    public void setDefaultProfile(String defaultProfile) {
        this.defaultProfile = defaultProfile;
    }

    public String getJvmOpts() {
        return jvmOpts;
    }

    public void setJvmOpts(String jvmOpts) {
        this.jvmOpts = jvmOpts;
    }

    public String getOtherOpts() {
        return otherOpts;
    }

    public void setOtherOpts(String otherOpts) {
        this.otherOpts = otherOpts;
    }
    @Override
    public String toString() {
        return name; // This is what will be displayed in the ComboBox
    }
}
