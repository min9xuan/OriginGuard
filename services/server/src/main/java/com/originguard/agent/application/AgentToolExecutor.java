package com.originguard.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Applies the same timeout, retry and circuit-breaker contract to every Agent tool. */
@Service
public class AgentToolExecutor {
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final int circuitFailureThreshold;
    private final Duration circuitOpenDuration;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public AgentToolExecutor(
            @Value("${originguard.agent.reliability.tool-timeout:PT12M}") Duration timeout,
            @Value("${originguard.agent.reliability.tool-max-attempts:2}") int maxAttempts,
            @Value("${originguard.agent.reliability.tool-retry-backoff:PT2S}") Duration retryBackoff,
            @Value("${originguard.agent.reliability.circuit-failure-threshold:3}") int circuitFailureThreshold,
            @Value("${originguard.agent.reliability.circuit-open-duration:PT1M}") Duration circuitOpenDuration) {
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = retryBackoff;
        this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
        this.circuitOpenDuration = circuitOpenDuration;
    }

    public Map<String, Object> execute(
            AgentTool tool, AgentExecutionContext context, Map<String, Object> input,
            AttemptListener listener) {
        CircuitState circuit = circuits.computeIfAbsent(tool.code(), ignored -> new CircuitState());
        if (circuit.isOpen(circuitOpenDuration)) {
            throw new AgentToolExecutionException("TOOL_CIRCUIT_OPEN",
                    "Tool circuit is open: " + tool.code());
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            listener.onAttempt(attempt, maxAttempts);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            Future<Map<String, Object>> future = executor.submit(() -> tool.execute(context, input));
            try {
                Map<String, Object> output = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                circuit.succeed();
                return output;
            } catch (TimeoutException exception) {
                future.cancel(true);
                last = new AgentToolExecutionException("TOOL_TIMEOUT",
                        "Tool timed out after " + timeout + ": " + tool.code(), exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AgentToolExecutionException("TOOL_INTERRUPTED", "Tool execution was interrupted", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                last = new AgentToolExecutionException("TOOL_EXECUTION_FAILED",
                        cause == null || cause.getMessage() == null ? tool.code() + " failed" : cause.getMessage(), cause);
            } finally {
                executor.shutdownNow();
            }
            circuit.fail(circuitFailureThreshold);
            if (attempt < maxAttempts) {
                listener.onRetry(attempt, last);
                try {
                    Thread.sleep(retryBackoff.multipliedBy(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AgentToolExecutionException("TOOL_INTERRUPTED", "Retry wait was interrupted", interrupted);
                }
            }
        }
        throw last;
    }

    @FunctionalInterface
    public interface AttemptListener {
        void onAttempt(int attempt, int maxAttempts);

        default void onRetry(int attempt, RuntimeException failure) {}
    }

    public static class AgentToolExecutionException extends RuntimeException {
        private final String code;

        public AgentToolExecutionException(String code, String message) { super(message); this.code = code; }
        public AgentToolExecutionException(String code, String message, Throwable cause) {
            super(message, cause); this.code = code;
        }
        public String code() { return code; }
    }

    private static final class CircuitState {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile Instant openedAt;

        private boolean isOpen(Duration duration) {
            Instant opened = openedAt;
            if (opened == null) return false;
            if (opened.plus(duration).isBefore(Instant.now())) {
                failures.set(0);
                openedAt = null;
                return false;
            }
            return true;
        }

        private void fail(int threshold) {
            if (failures.incrementAndGet() >= threshold) openedAt = Instant.now();
        }

        private void succeed() { failures.set(0); openedAt = null; }
    }
}
