package com.ashford.terminal.service;

/** An error that maps directly to an HTTP status + machine-readable code. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final String code;
    public ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
}
