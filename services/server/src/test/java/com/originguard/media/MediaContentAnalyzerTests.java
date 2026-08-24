package com.originguard.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.media.application.MediaContentAnalyzer;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MediaContentAnalyzerTests {
    private static final byte[] WEBP = Base64.getDecoder().decode(
            "UklGRh4AAABXRUJQVlA4TBEAAAAvAkAAAAfQluIVuf+BiOh/AAA=");

    private final MediaContentAnalyzer analyzer = new MediaContentAnalyzer();

    @Test
    void detectsAndDecodesWebpBeforeStorage() {
        MediaContentAnalyzer.Analysis analysis = analyzer.analyze(WEBP, "image/webp");

        assertThat(analysis.detectedContentType()).isEqualTo("image/webp");
        assertThat(analysis.width()).isEqualTo(3);
        assertThat(analysis.height()).isEqualTo(2);
        assertThat(analysis.perceptualHash()).matches("[0-9a-f]{16}");
        assertThat(analysis.sha256()).hasSize(64);
    }
}
