package com.originguard.knowledge.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeChunker {
    private static final int MAX_CHARACTERS = 800;
    private static final int OVERLAP = 120;

    public List<String> split(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + MAX_CHARACTERS, normalized.length());
            if (end < normalized.length()) {
                int paragraph = normalized.lastIndexOf("\n\n", end);
                if (paragraph > start + MAX_CHARACTERS / 2) end = paragraph;
            }
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= normalized.length()) break;
            int next = Math.max(start + 1, end - OVERLAP);
            int boundary = normalized.indexOf('\n', next);
            start = boundary >= 0 && boundary < end ? boundary + 1 : next;
        }
        return List.copyOf(chunks);
    }
}
