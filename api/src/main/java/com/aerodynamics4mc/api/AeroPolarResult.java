package com.aerodynamics4mc.api;

public final class AeroPolarResult {
    private final Status status;
    private final AeroPolarRequest request;
    private final AeroPolarTable table;
    private final String message;
    private final String runtimeInfo;

    private AeroPolarResult(
        Status status,
        AeroPolarRequest request,
        AeroPolarTable table,
        String message,
        String runtimeInfo
    ) {
        this.status = status == null ? Status.FAILED : status;
        this.request = request;
        this.table = table;
        this.message = message == null ? "" : message;
        this.runtimeInfo = runtimeInfo == null ? "" : runtimeInfo;
    }

    public static AeroPolarResult success(AeroPolarRequest request, AeroPolarTable table, String runtimeInfo) {
        if (request == null) {
            return failure(null, "request must not be null", runtimeInfo);
        }
        if (table == null) {
            return failure(request, "table must not be null", runtimeInfo);
        }
        return new AeroPolarResult(Status.OK, request, table, "", runtimeInfo);
    }

    public static AeroPolarResult unavailable(String message) {
        return new AeroPolarResult(Status.UNAVAILABLE, null, null, message, "");
    }

    public static AeroPolarResult failure(AeroPolarRequest request, String message, String runtimeInfo) {
        return new AeroPolarResult(Status.FAILED, request, null, message, runtimeInfo);
    }

    public Status status() {
        return status;
    }

    public boolean succeeded() {
        return status == Status.OK;
    }

    public boolean available() {
        return status != Status.UNAVAILABLE;
    }

    public AeroPolarRequest request() {
        return request;
    }

    public AeroPolarTable table() {
        return table;
    }

    public boolean hasTable() {
        return table != null;
    }

    public String message() {
        return message;
    }

    public String runtimeInfo() {
        return runtimeInfo;
    }

    public enum Status {
        OK,
        UNAVAILABLE,
        FAILED
    }
}
