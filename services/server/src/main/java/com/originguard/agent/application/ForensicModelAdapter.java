package com.originguard.agent.application;

import java.util.Map;

/**
 * Extension point for a real forensic model. New models register one Spring bean and
 * expose normalized raw results to the routing tool.
 */
public interface ForensicModelAdapter {
    ForensicModelCapability capability();

    Map<String, Object> analyze(byte[] content, String contentType);
}
