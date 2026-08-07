module plugin.javafxtools {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires org.java_websocket;
    requires java.xml;
    requires java.desktop;
    requires com.google.gson;

    opens plugin.javafxtools.controller to javafx.fxml;
    opens plugin.javafxtools.control to javafx.fxml;
    opens plugin.javafxtools.model to com.google.gson, com.fasterxml.jackson.databind;

    exports plugin.javafxtools;
}
