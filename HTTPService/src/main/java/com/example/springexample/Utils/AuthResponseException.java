package com.example.springexample.Utils;

public class AuthResponseException extends RuntimeException {
    private final String status;

    public AuthResponseException(String status, String message) {
        super(message);
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
