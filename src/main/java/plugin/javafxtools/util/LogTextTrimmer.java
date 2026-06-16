package plugin.javafxtools.util;

import javafx.scene.control.TextArea;

/**
 * TextArea 日志裁剪工具，集中处理各模块重复的按行删除旧日志逻辑。
 *
 * @author wwj
 */
public final class LogTextTrimmer {
    private LogTextTrimmer() {
    }

    /**
     * 按最大行数裁剪日志区域。达到上限时批量删除旧日志，避免逐行删除造成 UI 卡顿。
     *
     * @param area 日志文本区域
     * @param maxLines 最大保留行数
     * @param minimumRemovalLines 单次最少删除行数
     */
    public static void trimToMaxLines(TextArea area, int maxLines, int minimumRemovalLines) {
        if (area == null || maxLines <= 0) {
            return;
        }

        int trimIndex = findTrimStartIndex(area.getText(), area.getParagraphs().size(),
                maxLines, minimumRemovalLines);
        if (trimIndex > 0) {
            area.deleteText(0, trimIndex);
        }
    }

    /**
     * 计算应删除到的文本索引。
     *
     * @param text 原始文本
     * @param lineCount 当前行数
     * @param maxLines 最大保留行数
     * @param minimumRemovalLines 单次最少删除行数
     * @return 删除结束索引，0 表示无需删除
     */
    public static int findTrimStartIndex(String text, int lineCount, int maxLines, int minimumRemovalLines) {
        if (text == null || text.isEmpty() || maxLines <= 0 || lineCount < maxLines) {
            return 0;
        }

        int linesToRemove = Math.max(Math.max(1, minimumRemovalLines), lineCount - maxLines + 1);
        int deleteIndex = 0;
        while (linesToRemove > 0) {
            int nextNewline = text.indexOf('\n', deleteIndex);
            if (nextNewline < 0) {
                break;
            }
            deleteIndex = nextNewline + 1;
            linesToRemove--;
        }
        return deleteIndex;
    }
}
