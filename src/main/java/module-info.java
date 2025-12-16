module plugin.javafxtools {
    // 必需模块
    requires javafx.controls;        // JavaFX控件模块
    requires javafx.fxml;           // FXML支持模块
    requires com.fasterxml.jackson.databind; // JSON处理
    requires org.java_websocket;    // WebSocket客户端
    requires java.xml;
    requires com.google.gson;
    requires org.json;
    requires java.desktop;
    requires javafx.graphics;              // XML处理

    // 开放包给JavaFX FXML使用
    opens plugin.javafxtools to javafx.fxml;
    opens plugin.javafxtools.controller to javafx.fxml;

    // 导出包
    exports plugin.javafxtools;
    exports plugin.javafxtools.controller;
    exports plugin.javafxtools.service;
    exports plugin.javafxtools.model;
}