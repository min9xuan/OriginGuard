package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agent.application.AigcResultExplainer;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AigcResultExplainerTests {
    @Test
    void parsesStructuredChineseQwenExplanation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("originguard_aide_explanation", "image_url", "插画或卡通", "仅原始图像");
            byte[] response = """
                    {"choices":[{"message":{"content":"{\\"summary\\":\\"模型结果需要人工复核。\\",\\"supportingSignals\\":[\\"注意力集中在主体区域。\\"],\\"counterSignals\\":[\\"截图压缩可能影响结果。\\"],\\"limitations\\":[\\"热力图不是生成区域。\\"]}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AigcResultExplainer explainer = new AigcResultExplainer(
                    "local-qwen", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-model", Duration.ofSeconds(5), 64);
            Map<String, Object> explanation = explainer.explain(
                    "中文图片.png", imageBytes(), imageBytes(), detection());
            assertThat(explanation).containsEntry("source", "LOCAL_QWEN3_VL");
            assertThat(explanation.get("summary")).isEqualTo("模型结果需要人工复核。");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesExplicitChineseTemplateWhenQwenIsDisabled() throws Exception {
        AigcResultExplainer explainer = new AigcResultExplainer(
                "template", "http://127.0.0.1:1", "test-model", Duration.ofSeconds(1), 64);
        Map<String, Object> explanation = explainer.explain(
                "image.png", imageBytes(), imageBytes(), detection());
        assertThat(explanation).containsEntry("source", "DETERMINISTIC_TEMPLATE");
        assertThat(String.valueOf(explanation.get("summary"))).contains("插画或卡通", "91.0%", "专项校准");
    }

    private Map<String, Object> detection() {
        return Map.of(
                "classification", "LIKELY_SYNTHETIC",
                "syntheticProbability", 0.91,
                "authenticProbability", 0.09,
                "syntheticThreshold", 0.8,
                "authenticThreshold", 0.2,
                "mediaTypeContext", Map.of(
                        "provider", "OPENAI_CLIP", "status", "AVAILABLE",
                        "mediaType", "ILLUSTRATION_CARTOON", "mediaTypeLabel", "插画或卡通",
                        "mediaTypeScore", 0.9));
    }

    private byte[] imageBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 8, 8);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
