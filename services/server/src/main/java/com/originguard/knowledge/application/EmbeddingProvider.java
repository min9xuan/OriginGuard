package com.originguard.knowledge.application;

public interface EmbeddingProvider {
    String code();

    int dimensions();

    String embedAsVector(String text);
}
