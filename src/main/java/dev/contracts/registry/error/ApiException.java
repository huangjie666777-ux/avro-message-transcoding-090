package dev.contracts.registry.error;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "INVALID_REQUEST", message);
    }

    public static ApiException invalidSchema(String subject, String message) {
        return new ApiException(400, "INVALID_SCHEMA", "[" + subject + "] " + message);
    }

    public static ApiException incompatible(String message) {
        return new ApiException(422, "INCOMPATIBLE_SCHEMA", message);
    }

    public static ApiException versionConflict(String message) {
        return new ApiException(409, "VERSION_CONFLICT", message);
    }
}
