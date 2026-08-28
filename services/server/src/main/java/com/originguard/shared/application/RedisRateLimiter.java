package com.originguard.shared.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimiter {
    private final StringRedisTemplate redis;
    private final int limit;

    public RedisRateLimiter(StringRedisTemplate redis,
            @Value("${originguard.agent.rate-limit.requests-per-minute:20}") int limit) {
        this.redis = redis;
        this.limit = Math.max(1, limit);
    }

    public void requireAllowed(UUID userId, String action) {
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "originguard:rate:" + action + ":" + userId + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, Duration.ofMinutes(2));
            if (count != null && count > limit) {
                throw new BusinessConflictException(
                        "RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后再试（每分钟最多 " + limit + " 次）");
            }
        } catch (BusinessConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Fail open: a cache outage must not block forensic access.
        }
    }
}
