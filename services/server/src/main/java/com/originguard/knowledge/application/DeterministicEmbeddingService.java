package com.originguard.knowledge.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "originguard.embedding.provider",
        havingValue = "deterministic")
public class DeterministicEmbeddingService implements EmbeddingProvider {
    public static final int DIMENSIONS = 64;
    public static final String PROVIDER = "LOCAL_DETERMINISTIC_HASH_V1";

    @Override
    public String code() { return PROVIDER; }

    @Override
    public int dimensions() { return DIMENSIONS; }

    @Override
    public String embedAsVector(String text) {
        double[] vector = new double[DIMENSIONS];
        for (String feature : features(text)) {
            byte[] digest = sha256(feature);
            int bucket = Math.floorMod(ByteBuffer.wrap(digest, 0, 4).getInt(), DIMENSIONS);
            vector[bucket] += (digest[4] & 1) == 0 ? 1.0 : -1.0;
        }
        double norm = 0.0;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm == 0.0) vector[0] = 1.0;
        else for (int index = 0; index < vector.length; index++) vector[index] /= norm;
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) literal.append(',');
            literal.append(String.format(Locale.ROOT, "%.8f", vector[index]));
        }
        return literal.append(']').toString();
    }

    private List<String> features(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) token.appendCodePoint(codePoint);
            else flushToken(token, result);
        });
        flushToken(token, result);
        int[] compact = normalized.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).toArray();
        for (int index = 0; index + 1 < compact.length; index++) result.add("ng:" + new String(compact, index, 2));
        if (result.isEmpty()) result.add("empty");
        return result;
    }

    private void flushToken(StringBuilder token, List<String> result) {
        if (!token.isEmpty()) {
            result.add("tok:" + token);
            token.setLength(0);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
