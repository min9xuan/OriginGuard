package com.originguard.agent.application;

import java.util.Map;

public interface AgentTool {
    String code();

    Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input);
}
