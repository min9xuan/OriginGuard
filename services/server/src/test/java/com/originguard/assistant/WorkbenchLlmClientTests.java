package com.originguard.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.assistant.application.WorkbenchLlmClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkbenchLlmClientTests {
    private final WorkbenchLlmClient client = new WorkbenchLlmClient(
            "template", "http://127.0.0.1:1", "unused", Duration.ofSeconds(1));

    @Test
    void generalKnowledgeQuestionDoesNotStartMediaAgent() {
        var route = client.route("什么是 AIGC 图像检测，它的原理是什么？", List.of(), false);

        assertThat(route.intent()).isEqualTo(WorkbenchLlmClient.Intent.DIRECT_ANSWER);
        assertThat(route.needsWebSearch()).isFalse();
        assertThat(route.needsKnowledgeRetrieval()).isTrue();
    }

    @Test
    void greetingDoesNotSearchKnowledgeBase() {
        var route = client.route("你好", List.of(), false);

        assertThat(route.intent()).isEqualTo(WorkbenchLlmClient.Intent.DIRECT_ANSWER);
        assertThat(route.needsWebSearch()).isFalse();
        assertThat(route.needsKnowledgeRetrieval()).isFalse();
    }

    @Test
    void concreteImageQuestionRequiresAttachmentWhenConversationHasNone() {
        var route = client.route("这张图片是不是由 Sora 生成的？", List.of(), false);

        assertThat(route.intent()).isEqualTo(WorkbenchLlmClient.Intent.NEEDS_ATTACHMENT);
        assertThat(route.needsWebSearch()).isTrue();
    }

    @Test
    void concreteImageQuestionReusesPriorConversationAttachment() {
        var route = client.route("它是不是 AI 生成的？", List.of(), true);

        assertThat(route.intent()).isEqualTo(WorkbenchLlmClient.Intent.MEDIA_ANALYSIS);
    }

    @Test
    void explicitSuspiciousUrlStartsWebSecurityInvestigation() {
        var route = client.route("请检查 https://secure-login.example.com/account 这个网站是不是钓鱼网站", List.of(), false);

        assertThat(route.intent()).isEqualTo(WorkbenchLlmClient.Intent.WEB_SECURITY_INVESTIGATION);
        assertThat(route.needsWebSearch()).isTrue();
        assertThat(route.needsKnowledgeRetrieval()).isFalse();
    }

    @Test
    void generalPhishingQuestionRemainsDirectAnswer() {
        var route = client.route("什么是钓鱼网站？", List.of(), false);

        assertThat(route.intent()).isEqualTo(WorkbenchLlmClient.Intent.DIRECT_ANSWER);
    }
}
