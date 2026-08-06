package com.originguard.agent.application;

import com.originguard.shared.application.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(AgentTool::code, Function.identity()));
    }

    public AgentTool require(String code) {
        AgentTool tool = tools.get(code);
        if (tool == null) {
            throw new ResourceNotFoundException("AGENT_TOOL_NOT_FOUND", "Agent tool was not found");
        }
        return tool;
    }
}
