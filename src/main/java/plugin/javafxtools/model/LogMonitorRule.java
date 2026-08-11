package plugin.javafxtools.model;

public record LogMonitorRule(String id, String name, String expression,
                             LogMatchMode mode, boolean caseSensitive, boolean enabled) {
}
