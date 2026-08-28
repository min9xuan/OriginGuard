package com.originguard.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ForensicResultCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final Duration ttl;

    public ForensicResultCache(StringRedisTemplate redis,
            @Value("${originguard.agent.cache.enabled:true}") boolean enabled,
            @Value("${originguard.agent.cache.ttl:PT6H}") Duration ttl) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    public Optional<Map<String, Object>> get(String namespace, String identity) {
        if (!enabled) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key(namespace, identity));
            if (value == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(value, new TypeReference<>() {}));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public void put(String namespace, String identity, Map<String, Object> value) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(namespace, identity), objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // Cache failures never alter forensic results.
        }
    }

    private String key(String namespace, String identity) {
        return "originguard:forensic:v1:" + namespace + ":" + identity;
    }
}
