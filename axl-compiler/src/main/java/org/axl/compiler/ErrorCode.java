package org.axl.compiler;

public enum ErrorCode {
    SYNTAX("AXL001"),
    MISSING_PACKAGE("AXL002"),
    DOLLAR_IN_NAME("AXL003"),
    SHADOWING("AXL004"),
    RETURN_TRY("AXL005"),
    TRY_IN_EXPRESSION("AXL006"),
    TRY_OUTSIDE_RESULT_FUNCTION("AXL007"),
    ASSIGN_TO_VAL("AXL008"),
    TYPE_REQUIRED("AXL009"),
    DUPLICATE_NAME("AXL010"),
    RESERVED_NAME("AXL011"),
    INVALID_FILE_NAME("AXL012"),
    PIPE_STAGE("AXL013"),
    PACKAGE_MISMATCH("AXL014"),
    DUPLICATE_CLASS("AXL015"),
    INIT_ORDER("AXL016"),
    INTERFACE_MEMBER("AXL017"),
    JAVAC("AXL100"),
    TRY_ERROR_TYPE("AXL101"),
    NULLABLE_DEREFERENCE("AXL102"),
    IMPORT_MEMBER("AXL103"),
    STRICT_NULL("AXL104"),
    TRY_NOT_RESULT("AXL105");

    private final String id;

    ErrorCode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
