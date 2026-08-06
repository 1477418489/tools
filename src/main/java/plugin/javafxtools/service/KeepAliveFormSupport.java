package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;
import plugin.javafxtools.util.HttpUrlSupport;

/**
 * 域名保活配置表格、表单控件的初始化和取值。
 *
 * @author wwj
 */
public class KeepAliveFormSupport {
    private final TableView<KeepAliveConfig> configTableView;
    private final TableColumn<KeepAliveConfig, String> domainColumn;
    private final TableColumn<KeepAliveConfig, Boolean> enabledColumn;
    private final TableColumn<KeepAliveConfig, KeepAliveMethod> methodColumn;
    private final TableColumn<KeepAliveConfig, String> intervalColumn;
    private final TableColumn<KeepAliveConfig, IntervalUnit> unitColumn;
    private final TextField domainField;
    private final CheckBox enabledCheckBox;
    private final ComboBox<KeepAliveMethod> methodComboBox;
    private final Spinner<Integer> minIntervalSpinner;
    private final Spinner<Integer> maxIntervalSpinner;
    private final ComboBox<IntervalUnit> unitComboBox;
    private final ObservableList<KeepAliveConfig> configList;

    /**
     * 创建保活表单辅助对象。
     *
     * @param configTableView 配置表格
     * @param domainColumn 域名列
     * @param enabledColumn 启用状态列
     * @param methodColumn 保活方式列
     * @param intervalColumn 间隔范围列
     * @param unitColumn 间隔单位列
     * @param domainField 域名输入框
     * @param enabledCheckBox 启用状态复选框
     * @param methodComboBox 保活方式选择框
     * @param minIntervalSpinner 最小间隔输入器
     * @param maxIntervalSpinner 最大间隔输入器
     * @param unitComboBox 间隔单位选择框
     * @param configList 配置列表
     */
    public KeepAliveFormSupport(TableView<KeepAliveConfig> configTableView,
                                TableColumn<KeepAliveConfig, String> domainColumn,
                                TableColumn<KeepAliveConfig, Boolean> enabledColumn,
                                TableColumn<KeepAliveConfig, KeepAliveMethod> methodColumn,
                                TableColumn<KeepAliveConfig, String> intervalColumn,
                                TableColumn<KeepAliveConfig, IntervalUnit> unitColumn,
                                TextField domainField,
                                CheckBox enabledCheckBox,
                                ComboBox<KeepAliveMethod> methodComboBox,
                                Spinner<Integer> minIntervalSpinner,
                                Spinner<Integer> maxIntervalSpinner,
                                ComboBox<IntervalUnit> unitComboBox,
                                ObservableList<KeepAliveConfig> configList) {
        this.configTableView = configTableView;
        this.domainColumn = domainColumn;
        this.enabledColumn = enabledColumn;
        this.methodColumn = methodColumn;
        this.intervalColumn = intervalColumn;
        this.unitColumn = unitColumn;
        this.domainField = domainField;
        this.enabledCheckBox = enabledCheckBox;
        this.methodComboBox = methodComboBox;
        this.minIntervalSpinner = minIntervalSpinner;
        this.maxIntervalSpinner = maxIntervalSpinner;
        this.unitComboBox = unitComboBox;
        this.configList = configList;
    }

    /**
     * 初始化表格、下拉框、间隔输入器和选择监听。
     */
    public void initialize() {
        setupTableView();
        setupUnitComboBox();
        setupMethodComboBox();
        setupIntervalSpinners();
        setupSelectionListener();
    }

    /**
     * 从表单读取保活配置。
     *
     * @return 表单中的保活配置
     */
    public KeepAliveConfig readConfigFromForm() {
        return new KeepAliveConfig(
                getDomain(),
                enabledCheckBox.isSelected(),
                methodComboBox.getValue(),
                minIntervalSpinner.getValue(),
                maxIntervalSpinner.getValue(),
                unitComboBox.getValue()
        );
    }

    /**
     * 获取当前表单域名文本。
     *
     * @return 域名或 URL
     */
    public String getDomain() {
        String domain = domainField.getText();
        return domain == null ? "" : domain.trim();
    }

    /**
     * 获取当前最小间隔。
     *
     * @return 最小间隔
     */
    public int getMinInterval() {
        return minIntervalSpinner.getValue();
    }

    /**
     * 获取当前最大间隔。
     *
     * @return 最大间隔
     */
    public int getMaxInterval() {
        return maxIntervalSpinner.getValue();
    }

    /**
     * 清空配置编辑表单。
     */
    public void clearInputFields() {
        domainField.clear();
        enabledCheckBox.setSelected(true);
        methodComboBox.setValue(KeepAliveMethod.HTTP);
        minIntervalSpinner.getValueFactory().setValue(10);
        maxIntervalSpinner.getValueFactory().setValue(30);
        unitComboBox.setValue(IntervalUnit.MINUTES);
    }

    /**
     * 校验保活地址是否为 HTTP/HTTPS URL。
     *
     * @param url 待校验地址
     * @return 是否有效
     */
    public boolean isValidUrl(String url) {
        return HttpUrlSupport.isValid(url);
    }

    private void setupTableView() {
        domainColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getDomain()));

        enabledColumn.setCellValueFactory(cellData ->
                new SimpleBooleanProperty(cellData.getValue().isEnabled()));
        enabledColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean enabled, boolean empty) {
                super.updateItem(enabled, empty);
                setText(empty ? null : Boolean.TRUE.equals(enabled) ? "启用" : "停用");
                getStyleClass().removeAll("status-active", "status-muted");
                if (!empty) {
                    getStyleClass().add(Boolean.TRUE.equals(enabled) ? "status-active" : "status-muted");
                }
            }
        });

        methodColumn.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().getMethod()));

        intervalColumn.setCellValueFactory(cellData -> {
            KeepAliveConfig config = cellData.getValue();
            String intervalRange = String.format("%d-%d",
                    config.getMinInterval(),
                    config.getMaxInterval());
            return new SimpleStringProperty(intervalRange);
        });

        unitColumn.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().getUnit()));

        configTableView.setItems(configList);
        configTableView.setFixedCellSize(35);
        configTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        configTableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    }

    private void setupUnitComboBox() {
        unitComboBox.setItems(FXCollections.observableArrayList(
                IntervalUnit.MINUTES,
                IntervalUnit.HOURS,
                IntervalUnit.DAYS
        ));
        unitComboBox.setValue(IntervalUnit.MINUTES);
    }

    private void setupMethodComboBox() {
        methodComboBox.setItems(FXCollections.observableArrayList(
                KeepAliveMethod.HTTP,
                KeepAliveMethod.PING
        ));
        methodComboBox.setValue(KeepAliveMethod.HTTP);
    }

    private void setupIntervalSpinners() {
        minIntervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 1000, 10));

        maxIntervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 1000, 30));

        enabledCheckBox.setSelected(true);

        minIntervalSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal > maxIntervalSpinner.getValue()) {
                maxIntervalSpinner.getValueFactory().setValue(newVal);
            }
        });

        maxIntervalSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal < minIntervalSpinner.getValue()) {
                minIntervalSpinner.getValueFactory().setValue(newVal);
            }
        });
    }

    private void setupSelectionListener() {
        configTableView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showConfigDetails(newValue)
        );
    }

    private void showConfigDetails(KeepAliveConfig config) {
        if (config == null) {
            return;
        }

        Platform.runLater(() -> {
            domainField.setText(config.getDomain());
            enabledCheckBox.setSelected(config.isEnabled());
            methodComboBox.setValue(config.getMethod());
            minIntervalSpinner.getValueFactory().setValue(config.getMinInterval());
            maxIntervalSpinner.getValueFactory().setValue(config.getMaxInterval());
            unitComboBox.setValue(config.getUnit());
        });
    }
}
