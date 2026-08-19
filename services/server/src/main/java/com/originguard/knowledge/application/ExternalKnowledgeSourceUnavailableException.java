package com.originguard.knowledge.application;

public class ExternalKnowledgeSourceUnavailableException extends RuntimeException {
    private final String code;

    public ExternalKnowledgeSourceUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ExternalKnowledgeSourceUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
