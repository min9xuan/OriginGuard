package com.originguard.agent.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AigcEvidenceFusion {
    public static final String POLICY_VERSION = "2.0.0";

    public Map<String, Object> fuse(
            Map<String, Object> primary,
            Map<String, Object> mediaTypeContext,
            Map<String, Object> quality) {
        String qualityStatus = text(quality, "status", "PASS");
        String primaryVerdict = text(primary, "classification", "INCONCLUSIVE");
        String mediaTypeStatus = text(mediaTypeContext, "status", "UNAVAILABLE");
        String mediaType = text(mediaTypeContext, "mediaType", "UNKNOWN");
        String mediaTypeLabel = text(mediaTypeContext, "mediaTypeLabel", "类型不明确");
        List<String> reasons = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        String verdict;
        String confidence;
        String agreement;
        boolean decisionReady;

        if ("REJECT".equals(qualityStatus) || "UNSUPPORTED_INPUT".equals(primaryVerdict)) {
            verdict = "UNSUPPORTED_INPUT";
            confidence = "UNAVAILABLE";
            agreement = "NOT_EVALUATED";
            decisionReady = false;
            reasons.add("输入未通过质量门控，AIDE 未参与判定。");
            limitations.add("请提供分辨率和画面信息更完整的原始媒体后重新检测。");
        } else if (!"AVAILABLE".equals(mediaTypeStatus)) {
            verdict = "INCONCLUSIVE";
            confidence = "LOW";
            agreement = "MEDIA_TYPE_UNAVAILABLE";
            decisionReady = false;
            reasons.add("AIDE 已产生候选结果，但规划前没有获得可靠的媒体类型上下文。");
            limitations.add("缺少媒体类型时无法判断 AIDE 在当前内容域中的适用性。");
        } else if (!isLikely(primaryVerdict)) {
            verdict = "INCONCLUSIVE";
            confidence = "LOW";
            agreement = "PRIMARY_INCONCLUSIVE";
            decisionReady = false;
            reasons.add("CLIP 将媒体识别为“" + mediaTypeLabel + "”，但 AIDE 的生成痕迹得分处于不确定区间。");
            limitations.add("媒体类型只用于规划和解释，不能替代生成痕迹证据。");
        } else if (!"PHOTOGRAPH".equals(mediaType)) {
            verdict = "INCONCLUSIVE";
            confidence = "LOW";
            agreement = "DOMAIN_CALIBRATION_REQUIRED";
            decisionReady = false;
            reasons.add("AIDE 给出了“" + primaryVerdict + "”候选方向，但 CLIP 将媒体识别为“" + mediaTypeLabel + "”。");
            limitations.add("当前尚未完成该媒体类型的 AIDE 专项校准，不能把候选得分直接升级为真假结论。");
        } else {
            verdict = primaryVerdict;
            confidence = "LOW";
            agreement = "TYPE_CONTEXT_APPLIED";
            decisionReady = false;
            reasons.add("CLIP 将媒体识别为摄影图像，AIDE 的候选方向可按摄影图像域进行解释。");
            limitations.add("CLIP 不判断是否由 AI 生成，且 AIDE 仍是单一专用检测模型，结果不能直接作为人工裁决。");
        }
        if ("WARN".equals(qualityStatus)) {
            limitations.add("图像存在质量警告，模型输出需谨慎解释。");
        }
        limitations.add("AIDE 只接收原始图像，不能接收 CLIP 文本提示；媒体类型仅影响编排、适用性判断和结果解释。");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyVersion", POLICY_VERSION);
        result.put("verdict", verdict);
        result.put("confidence", confidence);
        result.put("agreement", agreement);
        result.put("decisionReady", decisionReady);
        result.put("reasons", List.copyOf(reasons));
        result.put("limitations", List.copyOf(limitations));
        return Map.copyOf(result);
    }

    private boolean isLikely(String verdict) {
        return "LIKELY_SYNTHETIC".equals(verdict) || "LIKELY_AUTHENTIC".equals(verdict);
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
