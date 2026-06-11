package com.mathvision.model;

import com.fasterxml.jackson.databind.JsonNode;

public class AiError {

    private String message;
    private String provider;
    private String model;
    private Integer httpStatus;
    private String responseBody;
    private String requestId;
    private String exceptionClass;
    private String stackTrace;
    private boolean rateLimited;
    private boolean transientFailure;
    private JsonNode raw;

    public static AiError fromException(Throwable error) {
        AiError aiError = new AiError();
        aiError.setMessage(error != null ? error.getMessage() : "Unknown AI error");
        aiError.setExceptionClass(error != null ? error.getClass().getName() : null);
        aiError.setStackTrace(error != null ? stackTraceToString(error) : null);
        return aiError;
    }

    private static String stackTraceToString(Throwable error) {
        java.io.StringWriter sw = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }

    public void setTransientFailure(boolean transientFailure) {
        this.transientFailure = transientFailure;
    }

    public JsonNode getRaw() {
        return raw;
    }

    public void setRaw(JsonNode raw) {
        this.raw = raw;
    }
}
