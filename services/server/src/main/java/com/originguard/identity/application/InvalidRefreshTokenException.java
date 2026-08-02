package com.originguard.identity.application;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Refresh token is invalid or expired");
    }
}

