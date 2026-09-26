package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.originguard.agent.application.AgentExecutionContext;
import com.originguard.agent.application.AgentTool;
import com.originguard.agent.application.AgentToolExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentToolExecutorTests {
    private final AgentExecutionContext context = new AgentExecutionContext(null, null, List.of(), 0);

    @Test
    void retriesTransientFailureAndReturnsSuccessfulResult() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool tool = tool("retry-tool", () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary");
            return Map.of("status", "SUCCEEDED");
        });
        AgentToolExecutor executor = new AgentToolExecutor(
                Duration.ofSeconds(1), 2, Duration.ZERO, 3, Duration.ofMinutes(1));

        Map<String, Object> result = executor.execute(tool, context, Map.of(), (attempt, max) -> {});

        assertThat(result).containsEntry("status", "SUCCEEDED");
        assertThat(calls).hasValue(2);
    }

    @Test
    void timesOutAndOpensToolSpecificCircuit() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool tool = tool("slow-tool", () -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        });
        AgentToolExecutor executor = new AgentToolExecutor(
                Duration.ofMillis(30), 1, Duration.ZERO, 1, Duration.ofMinutes(1));

        assertThatThrownBy(() -> executor.execute(tool, context, Map.of(), (attempt, max) -> {}))
                .isInstanceOf(AgentToolExecutor.AgentToolExecutionException.class)
                .hasMessageContaining("timed out");
        assertThatThrownBy(() -> executor.execute(tool, context, Map.of(), (attempt, max) -> {}))
                .isInstanceOf(AgentToolExecutor.AgentToolExecutionException.class)
                .hasMessageContaining("circuit is open");
        assertThat(calls).hasValue(1);
    }

    private AgentTool tool(String code, ThrowingSupplier supplier) {
        return new AgentTool() {
            @Override public String code() { return code; }
            @Override public Map<String, Object> execute(AgentExecutionContext ignored, Map<String, Object> input) {
                return supplier.get();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier { Map<String, Object> get(); }
}
