package plugin.javafxtools.model;

/**
 * JAR 项目列表状态汇总。
 */
public record JarProjectStatusSummary(int total,
                                      long running,
                                      long checking,
                                      long errors,
                                      long conflicts) {
    public String displayText() {
        if (total == 0) {
            return "暂无项目";
        }
        StringBuilder text = new StringBuilder(running + " / " + total + " 运行中");
        if (errors > 0) {
            text.append(" · ").append(errors).append(" 异常");
        }
        if (conflicts > 0) {
            text.append(" · ").append(conflicts).append(" 端口冲突");
        }
        return text.toString();
    }

    public Tone tone() {
        if (checking > 0) {
            return Tone.BUSY;
        }
        if (errors > 0 || conflicts > 0) {
            return Tone.ERROR;
        }
        return running > 0 ? Tone.ONLINE : Tone.OFFLINE;
    }

    public enum Tone {
        OFFLINE,
        BUSY,
        ONLINE,
        ERROR
    }
}
