package com.originguard.identity.application;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid tenant, username or password");
    }
}

