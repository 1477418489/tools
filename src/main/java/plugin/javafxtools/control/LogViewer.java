package plugin.javafxtools.control;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import plugin.javafxtools.util.FxTheme;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一的只读日志查看器。普通日志以紧凑入口打开独立窗口，核心消息流可按需内嵌；
 * 完整视图提供查找、复制、保存和显示控制。
 */
public final class LogViewer extends VBox {
    private static final double CONTENT_MIN_HEIGHT = 140;
    private static final int MIN_FONT_SIZE = 10;
    private static final int MAX_FONT_SIZE = 20;
    private static final int MAX_SEARCH_LENGTH = 256;
    private static final DateTimeFormatter EXPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Label titleLabel = new Label();
    private final Label statisticsLabel = new Label("0 行 · 0 字符");
    private final Label matchLabel = new Label("0 / 0");
    private final TextField searchField = new TextField();
    private final TextArea textArea = new TextArea();
    private final CheckBox autoScrollCheckBox = new CheckBox("跟随");
    private final CheckBox wrapTextCheckBox = new CheckBox("换行");
    private final Button previousButton = iconButton("↑", "上一个匹配");
    private final Button nextButton = iconButton("↓", "下一个匹配");
    private final Button copyButton = new Button("复制全部");
    private final Button saveButton = new Button("保存");
    private final Button clearButton = new Button("清空");
    private final Button detachButton = new Button("查看");
    private final Button decreaseFontButton = iconButton("−", "减小字号");
    private final Button increaseFontButton = iconButton("+", "增大字号");
    private final PauseTransition searchRefreshDelay = new PauseTransition(Duration.millis(180));
    private final AtomicBoolean scrollScheduled = new AtomicBoolean();
    private final HBox displayOptions = new HBox(7);
    private final VBox contentPane = new VBox();
    private final boolean detachedMode;

    private Runnable clearAction = textArea::clear;
    private Stage detachedStage;
    private int currentMatchIndex = -1;
    private int currentMatchCount;
    private int currentMatchOrdinal;
    private int fontSize = 12;
    private boolean queryChanged;
    private String windowTitle = "";
    private boolean inlineContent;

    public LogViewer() {
        this(false);
    }

    private LogViewer(boolean detachedMode) {
        this.detachedMode = detachedMode;
        getStyleClass().add("log-viewer");

        configureTextArea();
        configureToolbarActions();
        configureContentPane();
        getChildren().addAll(createHeader(), contentPane);
        VBox.setVgrow(contentPane, Priority.ALWAYS);

        autoScrollCheckBox.setSelected(true);
        updateFontSize();
        updateTextState("");
        updateMatchLabel();
        applyContentMode();
    }

    public TextArea getTextArea() {
        return textArea;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    public void setTitle(String title) {
        String value = title == null ? "" : title;
        titleLabel.setText(value);
        boolean visible = !value.isBlank();
        titleLabel.setVisible(visible);
        titleLabel.setManaged(visible);
    }

    public String getWindowTitle() {
        return windowTitle;
    }

    public void setWindowTitle(String windowTitle) {
        this.windowTitle = windowTitle == null ? "" : windowTitle;
    }

    public boolean isWrapText() {
        return wrapTextCheckBox.isSelected();
    }

    public void setWrapText(boolean wrapText) {
        wrapTextCheckBox.setSelected(wrapText);
    }

    public boolean isAutoScroll() {
        return autoScrollCheckBox.isSelected();
    }

    public void setAutoScroll(boolean autoScroll) {
        autoScrollCheckBox.setSelected(autoScroll);
    }

    public boolean isInlineContent() {
        return inlineContent;
    }

    public void setInlineContent(boolean inlineContent) {
        if (detachedMode) {
            return;
        }
        this.inlineContent = inlineContent;
        applyContentMode();
    }

    public String getPromptText() {
        return textArea.getPromptText();
    }

    public void setPromptText(String promptText) {
        textArea.setPromptText(promptText);
    }

    public void setOnClear(Runnable clearAction) {
        this.clearAction = clearAction == null ? textArea::clear : clearAction;
    }

    public void clear() {
        requestClear();
    }

    private void scrollToEndIfEnabled() {
        if (!isContentVisible() || !autoScrollCheckBox.isSelected()
                || !scrollScheduled.compareAndSet(false, true)) {
            return;
        }
        Runnable scroll = () -> {
            try {
                if (isContentVisible() && autoScrollCheckBox.isSelected()) {
                    textArea.setScrollTop(Double.MAX_VALUE);
                }
            } finally {
                scrollScheduled.set(false);
            }
        };
        try {
            Platform.runLater(scroll);
        } catch (IllegalStateException ignored) {
            scrollScheduled.set(false);
        }
    }

    private HBox createHeader() {
        titleLabel.getStyleClass().add("log-viewer-title");
        titleLabel.setMaxWidth(190);
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        statisticsLabel.getStyleClass().add("log-viewer-meta");
        titleLabel.setOnMouseClicked(this::openLogWindowFromHeader);
        statisticsLabel.setOnMouseClicked(this::openLogWindowFromHeader);
        detachButton.getStyleClass().add("log-command-button");
        detachButton.setTooltip(new Tooltip("打开完整日志窗口"));
        autoScrollCheckBox.setTooltip(new Tooltip("自动跟随最新日志"));
        wrapTextCheckBox.setTooltip(new Tooltip("按查看器宽度自动换行"));
        displayOptions.getChildren().addAll(autoScrollCheckBox, wrapTextCheckBox);
        displayOptions.setAlignment(Pos.CENTER_LEFT);

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, titleLabel, statisticsLabel, titleSpacer,
                displayOptions, detachButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("log-viewer-header");
        return header;
    }

    private void openLogWindowFromHeader(MouseEvent event) {
        if (event.getClickCount() == 1 && !isContentVisible()) {
            openDetachedWindow();
        }
    }

    private void configureContentPane() {
        matchLabel.getStyleClass().add("log-viewer-match");
        matchLabel.setMinWidth(58);
        matchLabel.setAlignment(Pos.CENTER_RIGHT);
        decreaseFontButton.getStyleClass().add("log-font-button");
        increaseFontButton.getStyleClass().add("log-font-button");

        searchField.setPromptText("搜索日志");
        searchField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= MAX_SEARCH_LENGTH ? change : null));
        searchField.getStyleClass().add("log-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        previousButton.getStyleClass().add("log-nav-button");
        nextButton.getStyleClass().add("log-nav-button");
        copyButton.getStyleClass().add("log-command-button");
        saveButton.getStyleClass().add("log-command-button");
        clearButton.getStyleClass().add("log-command-button");
        copyButton.setMinWidth(68);
        saveButton.setMinWidth(50);
        clearButton.setMinWidth(50);
        HBox searchRow = new HBox(6, searchField, previousButton, nextButton,
                matchLabel, decreaseFontButton, increaseFontButton,
                copyButton, saveButton, clearButton);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.getStyleClass().add("log-viewer-searchbar");

        contentPane.getStyleClass().add("log-viewer-content");
        contentPane.getChildren().addAll(searchRow, textArea);
        VBox.setVgrow(textArea, Priority.ALWAYS);
    }

    private void configureTextArea() {
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.getStyleClass().add("log-area");
        textArea.setContextMenu(createContextMenu());
        textArea.textProperty().addListener((observable, oldValue, newValue) -> {
            updateTextState(newValue == null ? "" : newValue);
            scheduleSearchRefresh();
            scrollToEndIfEnabled();
        });
        textArea.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() != 0) {
                autoScrollCheckBox.setSelected(false);
            }
        });
        textArea.addEventFilter(MouseEvent.MOUSE_PRESSED,
                event -> autoScrollCheckBox.setSelected(false));
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (isManualNavigationKey(event.getCode())) {
                autoScrollCheckBox.setSelected(false);
            }
        });
    }

    private void configureToolbarActions() {
        searchRefreshDelay.setOnFinished(event -> refreshSearchState());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            queryChanged = true;
            currentMatchIndex = -1;
            if (newValue != null && !newValue.isBlank()) {
                autoScrollCheckBox.setSelected(false);
            }
            if (newValue == null || newValue.isBlank()) {
                searchRefreshDelay.stop();
                refreshSearchState();
            } else {
                searchRefreshDelay.playFromStart();
            }
        });
        searchField.setOnAction(event -> findNext());
        previousButton.setOnAction(event -> findPrevious());
        nextButton.setOnAction(event -> findNext());
        copyButton.setOnAction(event -> copyAll());
        saveButton.setOnAction(event -> saveToFile());
        clearButton.setOnAction(event -> requestClear());
        detachButton.setOnAction(event -> openDetachedWindow());
        decreaseFontButton.setOnAction(event -> setFontSize(fontSize - 1));
        increaseFontButton.setOnAction(event -> setFontSize(fontSize + 1));
        wrapTextCheckBox.selectedProperty().addListener(
                (observable, oldValue, selected) -> textArea.setWrapText(selected));
        autoScrollCheckBox.selectedProperty().addListener((observable, oldValue, selected) -> {
            if (selected) {
                scrollToEndIfEnabled();
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardNavigation);
    }

    private void applyContentMode() {
        boolean contentVisible = isContentVisible();
        contentPane.setVisible(contentVisible);
        contentPane.setManaged(contentVisible);
        displayOptions.setVisible(contentVisible);
        displayOptions.setManaged(contentVisible);
        detachButton.setVisible(!detachedMode);
        detachButton.setManaged(!detachedMode);
        getStyleClass().remove("log-viewer-inline");
        if (contentVisible) {
            getStyleClass().add("log-viewer-inline");
            setMinHeight(CONTENT_MIN_HEIGHT);
            setMaxHeight(Double.MAX_VALUE);
        } else {
            setMinHeight(Region.USE_PREF_SIZE);
            setMaxHeight(Region.USE_PREF_SIZE);
        }
        requestLayout();
        if (contentVisible) {
            scrollToEndIfEnabled();
            scheduleSearchRefresh();
        }
    }

    private boolean isContentVisible() {
        return detachedMode || inlineContent;
    }

    private ContextMenu createContextMenu() {
        MenuItem copySelection = new MenuItem("复制所选");
        copySelection.setOnAction(event -> textArea.copy());
        MenuItem selectAll = new MenuItem("全选");
        selectAll.setOnAction(event -> textArea.selectAll());
        MenuItem copyAll = new MenuItem("复制全部");
        copyAll.setOnAction(event -> copyAll());
        MenuItem save = new MenuItem("保存到文件");
        save.setOnAction(event -> saveToFile());
        MenuItem clear = new MenuItem("清空");
        clear.setOnAction(event -> requestClear());
        ContextMenu menu = new ContextMenu(copySelection, selectAll, copyAll, save, clear);
        menu.setOnShowing(event -> {
            copySelection.setDisable(textArea.getSelectedText().isEmpty());
            boolean empty = textArea.getText().isEmpty();
            selectAll.setDisable(empty);
            copyAll.setDisable(empty);
            save.setDisable(empty);
            clear.setDisable(empty);
        });
        return menu;
    }

    private void handleKeyboardNavigation(KeyEvent event) {
        if (event.isControlDown() && event.getCode() == KeyCode.F) {
            if (!isContentVisible()) {
                openDetachedWindow();
                event.consume();
                return;
            }
            searchField.requestFocus();
            searchField.selectAll();
            event.consume();
        } else if (event.getCode() == KeyCode.F3) {
            if (!isContentVisible()) {
                openDetachedWindow();
                event.consume();
                return;
            }
            if (event.isShiftDown()) {
                findPrevious();
            } else {
                findNext();
            }
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE
                && searchField.isFocused() && !searchField.getText().isEmpty()) {
            searchField.clear();
            event.consume();
        }
    }

    private boolean isManualNavigationKey(KeyCode code) {
        return code == KeyCode.UP
                || code == KeyCode.DOWN
                || code == KeyCode.PAGE_UP
                || code == KeyCode.PAGE_DOWN
                || code == KeyCode.HOME
                || code == KeyCode.END;
    }

    private void updateTextState(String text) {
        int lineCount = text.isEmpty() ? 0 : textArea.getParagraphs().size();
        if (lineCount > 0 && (text.endsWith("\n") || text.endsWith("\r"))) {
            lineCount--;
        }
        statisticsLabel.setText(lineCount + " 行 · " + compactCharacterCount(text.length()) + " 字符");
        boolean empty = text.isEmpty();
        copyButton.setDisable(empty);
        saveButton.setDisable(empty);
        clearButton.setDisable(empty);
    }

    private String compactCharacterCount(int count) {
        if (count < 1_000) {
            return String.valueOf(count);
        }
        if (count < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fK", count / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
    }

    private void scheduleSearchRefresh() {
        if (isContentVisible() && !searchField.getText().isBlank()) {
            searchRefreshDelay.playFromStart();
        }
    }

    private void refreshSearchState() {
        String text = textArea.getText();
        String query = searchField.getText();
        LogSearchSupport.SearchSummary summary =
                LogSearchSupport.summarize(text, query, currentMatchIndex);
        currentMatchCount = summary.matchCount();

        if (currentMatchCount == 0) {
            currentMatchIndex = -1;
            currentMatchOrdinal = 0;
            textArea.deselect();
        } else if (queryChanged) {
            currentMatchIndex = summary.firstMatchIndex();
            currentMatchOrdinal = 1;
            selectCurrentMatch();
        } else if (summary.selectedOrdinal() > 0) {
            currentMatchOrdinal = summary.selectedOrdinal();
        } else {
            currentMatchIndex = -1;
            currentMatchOrdinal = 0;
            textArea.deselect();
        }
        queryChanged = false;
        updateMatchLabel();
    }

    private void findNext() {
        String text = textArea.getText();
        String query = searchField.getText();
        int start = currentMatchIndex >= 0
                ? currentMatchIndex + 1
                : textArea.getCaretPosition();
        applySearchResult(LogSearchSupport.findNextWithStats(text, query, start));
    }

    private void findPrevious() {
        String text = textArea.getText();
        String query = searchField.getText();
        int start = currentMatchIndex >= 0
                ? currentMatchIndex - 1
                : textArea.getCaretPosition() - 1;
        applySearchResult(LogSearchSupport.findPreviousWithStats(text, query, start));
    }

    private void applySearchResult(LogSearchSupport.SearchResult result) {
        searchRefreshDelay.stop();
        queryChanged = false;
        currentMatchIndex = result.matchIndex();
        currentMatchOrdinal = result.ordinal();
        currentMatchCount = result.matchCount();
        selectCurrentMatch();
        updateMatchLabel();
    }

    private void selectCurrentMatch() {
        String query = searchField.getText();
        if (currentMatchIndex >= 0 && query != null && !query.isEmpty()) {
            textArea.selectRange(currentMatchIndex, currentMatchIndex + query.length());
        }
    }

    private void updateMatchLabel() {
        matchLabel.setText(currentMatchOrdinal + " / " + currentMatchCount);
        previousButton.setDisable(currentMatchCount == 0);
        nextButton.setDisable(currentMatchCount == 0);
    }

    private void copyAll() {
        String text = textArea.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void saveToFile() {
        String text = textArea.getText();
        if (text == null || text.isEmpty()) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存日志");
        chooser.setInitialFileName("fxtools-log-"
                + LocalDateTime.now().format(EXPORT_TIME_FORMAT) + ".txt");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"));
        Window owner = getScene() == null ? null : getScene().getWindow();
        File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        Thread.ofVirtual().name("LogExporter").start(() -> {
            try {
                Files.writeString(target.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException | SecurityException e) {
                showSaveError(e.getMessage());
            }
        });
    }

    private void showSaveError(String details) {
        try {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                FxTheme.apply(alert);
                alert.setTitle("保存失败");
                alert.setHeaderText("无法保存日志文件");
                alert.setContentText(details == null || details.isBlank()
                        ? "请检查目标目录权限后重试" : details);
                alert.showAndWait();
            });
        } catch (IllegalStateException ignored) {
            // JavaFX 已关闭，无需再显示错误窗口。
        }
    }

    private void requestClear() {
        clearAction.run();
        currentMatchIndex = -1;
        currentMatchOrdinal = 0;
        refreshSearchState();
    }

    private void setFontSize(int requestedSize) {
        fontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, requestedSize));
        updateFontSize();
    }

    private void updateFontSize() {
        textArea.setStyle("-fx-font-size: " + fontSize + "px;");
        decreaseFontButton.setDisable(fontSize <= MIN_FONT_SIZE);
        increaseFontButton.setDisable(fontSize >= MAX_FONT_SIZE);
    }

    private void openDetachedWindow() {
        if (detachedStage != null) {
            detachedStage.show();
            detachedStage.toFront();
            detachedStage.requestFocus();
            return;
        }

        LogViewer detachedViewer = new LogViewer(true);
        detachedViewer.setTitle(resolveWindowTitle());
        detachedViewer.setWrapText(isWrapText());
        detachedViewer.setAutoScroll(isAutoScroll());
        detachedViewer.setFontSize(fontSize);
        detachedViewer.setOnClear(this::requestClear);
        detachedViewer.getTextArea().textProperty().bind(textArea.textProperty());

        StackPane shell = new StackPane(detachedViewer);
        shell.getStyleClass().add("detached-log-shell");
        shell.setPadding(new Insets(12));
        Scene detachedScene = new Scene(shell, 980, 640);
        if (getScene() != null) {
            detachedScene.getStylesheets().setAll(getScene().getStylesheets());
        }

        Stage stage = new Stage();
        Window owner = getScene() == null ? null : getScene().getWindow();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle(resolveWindowTitle());
        stage.setMinWidth(720);
        stage.setMinHeight(460);
        stage.setScene(detachedScene);
        stage.setOnHidden(event -> {
            detachedViewer.getTextArea().textProperty().unbind();
            detachedStage = null;
        });
        detachedStage = stage;
        stage.show();
    }

    private String resolveWindowTitle() {
        if (!windowTitle.isBlank()) {
            return windowTitle;
        }
        return getTitle().isBlank() ? "日志查看器" : getTitle();
    }

    private static Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("log-icon-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }
}
