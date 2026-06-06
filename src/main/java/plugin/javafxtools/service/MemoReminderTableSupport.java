package plugin.javafxtools.service;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import plugin.javafxtools.model.MemoReminder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 备忘提醒表格列绑定和展示格式化。
 *
 * @author wwj
 */
public class MemoReminderTableSupport {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TableView<MemoReminder> reminderTable;
    private final TableColumn<MemoReminder, String> contentCol;
    private final TableColumn<MemoReminder, String> intervalCol;
    private final TableColumn<MemoReminder, String> remainCol;
    private final TableColumn<MemoReminder, String> nextTimeCol;
    private final TableColumn<MemoReminder, String> statusCol;
    private final ObservableList<MemoReminder> reminders;

    /**
     * 创建表格辅助对象。
     *
     * @param reminderTable 提醒表格
     * @param contentCol 内容列
     * @param intervalCol 间隔列
     * @param remainCol 剩余次数列
     * @param nextTimeCol 下次提醒时间列
     * @param statusCol 状态列
     * @param reminders 提醒列表
     */
    public MemoReminderTableSupport(TableView<MemoReminder> reminderTable,
                                    TableColumn<MemoReminder, String> contentCol,
                                    TableColumn<MemoReminder, String> intervalCol,
                                    TableColumn<MemoReminder, String> remainCol,
                                    TableColumn<MemoReminder, String> nextTimeCol,
                                    TableColumn<MemoReminder, String> statusCol,
                                    ObservableList<MemoReminder> reminders) {
        this.reminderTable = reminderTable;
        this.contentCol = contentCol;
        this.intervalCol = intervalCol;
        this.remainCol = remainCol;
        this.nextTimeCol = nextTimeCol;
        this.statusCol = statusCol;
        this.reminders = reminders;
    }

    /**
     * 初始化提醒表格列绑定。
     */
    public void initialize() {
        reminderTable.setItems(reminders);
        contentCol.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getContent()));
        intervalCol.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getDisplayInterval()));
        remainCol.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getDisplayRemaining()));
        nextTimeCol.setCellValueFactory(data ->
                new SimpleStringProperty(formatTime(data.getValue().getNextTriggerEpochMillis())));
        statusCol.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().isActive() ? "运行中" : "已暂停"));
    }

    private String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }
}
