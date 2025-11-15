package org.axl.compiler;

public record AxlDiagnostic(String file, int line, int column, Severity severity, ErrorCode code, String message) {

    public enum Severity { ERROR, WARNING }

    public static AxlDiagnostic error(String file, int line, int column, ErrorCode code, String message) {
        return new AxlDiagnostic(file, line, column, Severity.ERROR, code, message);
    }

    public static AxlDiagnostic warning(String file, int line, int column, ErrorCode code, String message) {
        return new AxlDiagnostic(file, line, column, Severity.WARNING, code, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** `file.axl:line:column: error: message`, the format every tool prints. */
    public String format() {
        return file + ":" + line + ":" + column + ": " + severity.name().toLowerCase() + ": " + message;
    }
}
