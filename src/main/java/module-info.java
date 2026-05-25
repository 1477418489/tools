module plugin.javafxtools {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires com.fasterxml.jackson.databind;
    requires org.java_websocket;
    requires java.xml;
    requires com.google.gson;
    requires java.desktop;

    opens plugin.javafxtools to javafx.fxml;
    opens plugin.javafxtools.controller to javafx.fxml;

    exports plugin.javafxtools;
    exports plugin.javafxtools.base;
    exports plugin.javafxtools.controller;
    exports plugin.javafxtools.service;
    exports plugin.javafxtools.model;
}