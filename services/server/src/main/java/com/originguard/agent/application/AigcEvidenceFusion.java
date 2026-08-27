package com.originguard.agent.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AigcEvidenceFusion {
    public static final String POLICY_VERSION = "3.0.0";

    public Map<String, Object> fuse(
            Map<String, Object> primary,
            Map<String, Object> mediaTypeContext,
            Map<String, Object> quality) {
        return fuse(primary, mediaTypeContext, quality, Map.of());
    }

    public Map<String, Object> fuse(
            Map<String, Object> primary,
            Map<String, Object> mediaTypeContext,
            Map<String, Object> quality,
            Map<String, Object> secondaryVerification) {
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
            reasons.add("输入未通过质量门控，生成内容鉴别模型未参与判定。");
            limitations.add("请提供分辨率和画面信息更完整的原始媒体后重新检测。");
        } else if (!isLikely(primaryVerdict)) {
            verdict = "INCONCLUSIVE";
            confidence = "LOW";
            agreement = "PRIMARY_INCONCLUSIVE";
            decisionReady = false;
            reasons.add("CLIP 将媒体识别为“" + mediaTypeLabel + "”，但生成内容鉴别模型的得分处于不确定区间。");
            limitations.add("媒体类型只用于规划和解释，不能替代生成痕迹证据。");
        } else {
            verdict = primaryVerdict;
            confidence = confidence(primary, qualityStatus, mediaTypeStatus, mediaType);
            agreement = "AVAILABLE".equals(mediaTypeStatus)
                    ? "PRELIMINARY_WITH_TYPE_CONTEXT" : "PRELIMINARY_WITHOUT_TYPE_CONTEXT";
            decisionReady = true;
            reasons.add("生成内容鉴别模型已依据 0.5 实验决策阈值形成“" + primaryVerdict + "”的初步方向。");
            if ("AVAILABLE".equals(mediaTypeStatus)) {
                reasons.add("CLIP 将媒体识别为“" + mediaTypeLabel + "”，该类型仅用于路由后续专用模型和解释适用边界。");
            } else {
                limitations.add("本次未取得 CLIP 媒体类型，无法执行面向内容域的模型路由。");
            }
            boolean specialized = "ILLUSTRATION_AIGC_DETECTOR".equals(primary.get("provider"));
            if (!"PHOTOGRAPH".equals(mediaType) && !specialized) {
                limitations.add("尚未接入“" + mediaTypeLabel + "”专用 AIGC 检测模型，当前初步判断主要来自通用生成内容鉴别模型。");
            }
            if (specialized) {
                reasons.add("已使用匹配“" + mediaTypeLabel + "”内容域的专用模型执行检测与区域定位。");
            }
            limitations.add("这是 Agent 的模型初步判断，仍需由你结合证据完成最终人工核验。");
        }
        String secondaryStatus = text(secondaryVerification, "status", "NOT_RUN");
        String secondaryVerdict = text(secondaryVerification, "classification", "INCONCLUSIVE");
        if ("SUCCEEDED".equals(secondaryStatus)) {
            if ("LIKELY_DIFFUSION_GENERATED".equals(secondaryVerdict)) {
                reasons.add("扩散重建复核发现与扩散模型生成相符的低重建距离信号。");
            } else if ("NO_DIFFUSION_SIGNAL".equals(secondaryVerdict)) {
                reasons.add("扩散重建复核未发现达到已校准阈值的扩散生成信号。");
            } else {
                limitations.add("已取得扩散重建距离，但尚未配置验证集阈值，因此不参与方向性投票。");
            }
        }
        if ("WARN".equals(qualityStatus)) {
            limitations.add("图像存在质量警告，模型输出需谨慎解释。");
        }
        limitations.add("生成内容鉴别模型只接收原始图像，不能接收 CLIP 文本提示；媒体类型仅影响编排、适用性判断和结果解释。");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyVersion", POLICY_VERSION);
        result.put("verdict", verdict);
        result.put("confidence", confidence);
        result.put("agreement", agreement);
        result.put("decisionReady", decisionReady);
        result.put("assessmentLevel", "AGENT_PRELIMINARY");
        result.put("humanReviewRequired", true);
        result.put("recommendedDomainDetector", recommendedDomainDetector(mediaType));
        result.put("specializedDetectorStatus", "ILLUSTRATION_AIGC_DETECTOR".equals(primary.get("provider"))
                ? "USED" : "NOT_USED");
        result.put("secondaryVerificationStatus", secondaryStatus);
        result.put("reasons", List.copyOf(reasons));
        result.put("limitations", List.copyOf(limitations));
        return Map.copyOf(result);
    }

    private boolean isLikely(String verdict) {
        return "LIKELY_SYNTHETIC".equals(verdict) || "LIKELY_AUTHENTIC".equals(verdict);
    }

    private String confidence(Map<String, Object> primary, String qualityStatus, String mediaTypeStatus, String mediaType) {
        double probability = number(primary.get("syntheticProbability"), 0.5);
        double distance = Math.abs(probability - 0.5);
        String value = distance >= 0.30 ? "HIGH" : distance >= 0.15 ? "MEDIUM" : "LOW";
        if ("WARN".equals(qualityStatus) || !"AVAILABLE".equals(mediaTypeStatus)
                || (!"PHOTOGRAPH".equals(mediaType) && "HIGH".equals(value))) {
            return "HIGH".equals(value) ? "MEDIUM" : "MEDIUM".equals(value) ? "LOW" : value;
        }
        return value;
    }

    private String recommendedDomainDetector(String mediaType) {
        return switch (mediaType) {
            case "ILLUSTRATION_CARTOON" -> "CARTOON_AIGC_DETECTOR";
            case "THREE_D_RENDER" -> "CGI_AIGC_DETECTOR";
            case "DOCUMENT_SCREENSHOT" -> "SCREENSHOT_FORENSICS_DETECTOR";
            case "DIAGRAM_GRAPHIC" -> "GRAPHIC_AIGC_DETECTOR";
            default -> "GENERAL_AIGC_DETECTOR";
        };
    }

    private double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
