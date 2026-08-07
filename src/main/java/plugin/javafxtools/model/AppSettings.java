package plugin.javafxtools.model;

/**
 * 应用级运行设置。
 *
 * @param closeToTray 关闭主窗口时隐藏到托盘
 * @param reminderSoundEnabled 提醒弹窗时播放提示音
 * @param startWithWindows 随 Windows 登录启动
 */
public record AppSettings(boolean closeToTray,
                          boolean reminderSoundEnabled,
                          boolean startWithWindows) {
    public static AppSettings defaults() {
        return new AppSettings(true, true, false);
    }
}
