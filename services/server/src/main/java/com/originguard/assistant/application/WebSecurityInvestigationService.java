package com.originguard.assistant.application;

import com.originguard.shared.application.BusinessConflictException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Service;

/** A defensive, SSRF-aware URL investigation that never downloads target-controlled page content. */
@Service
public class WebSecurityInvestigationService {
    private static final int NETWORK_TIMEOUT_MILLIS = 5000;
    private final WebSecurityTargetPolicy targetPolicy;
    private final LiveWebSearchClient webSearch;

    public WebSecurityInvestigationService(
            WebSecurityTargetPolicy targetPolicy, LiveWebSearchClient webSearch) {
        this.targetPolicy = targetPolicy;
        this.webSearch = webSearch;
    }

    public Result investigate(String question) {
        long started = System.nanoTime();
        URI target = targetPolicy.requireTarget(question);
        String host = targetPolicy.asciiHost(target);
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(trace("TARGET_POLICY", "SUCCEEDED", "URL 协议、凭据、主机名与端口策略校验通过"));

        List<InetAddress> addresses = resolve(host);
        targetPolicy.requirePublicAddresses(target, addresses);
        trace.add(trace("DNS_RESOLUTION", "SUCCEEDED", "域名仅解析到允许调查的公网地址"));

        Map<String, Object> tls = "https".equalsIgnoreCase(target.getScheme())
                ? inspectTls(host, targetPolicy.effectivePort(target), addresses)
                : Map.of("status", "NOT_APPLICABLE", "reason", "目标使用 HTTP，未建立 TLS 会话");
        trace.add(trace("TLS_INSPECTION", String.valueOf(tls.get("status")), tlsSummary(tls)));

        List<Map<String, Object>> signals = riskSignals(target, host, tls);
        int riskScore = Math.min(100, signals.stream().mapToInt(item -> ((Number) item.get("points")).intValue()).sum());
        String riskLevel = riskLevel(riskScore);

        LiveWebSearchClient.SearchResponse intelligence = webSearch.searchGeneralWeb(
                "\"" + host + "\" phishing OR malware OR scam OR fraud", 5);
        trace.add(trace("PUBLIC_THREAT_SEARCH",
                intelligence.sources().isEmpty() ? "NO_RESULTS" : "SUCCEEDED",
                intelligence.sources().isEmpty()
                        ? "未取得可用的普通网页威胁线索；这不代表目标安全"
                        : "取得 " + intelligence.sources().size() + " 条公开网页线索，等待人工核实"));
        trace.add(trace("RISK_SYNTHESIS", "SUCCEEDED", "使用确定性规则形成风险分，不让网页文本直接修改结论"));

        List<String> addressStrings = addresses.stream().map(InetAddress::getHostAddress).distinct().toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("targetUrl", target.toASCIIString());
        report.put("host", host);
        report.put("scheme", target.getScheme().toUpperCase(Locale.ROOT));
        report.put("port", targetPolicy.effectivePort(target));
        report.put("resolvedAddresses", addressStrings);
        report.put("ioc", Map.of("url", target.toASCIIString(), "domain", host, "ipAddresses", addressStrings));
        report.put("tls", tls);
        report.put("riskScore", riskScore);
        report.put("riskLevel", riskLevel);
        report.put("signals", List.copyOf(signals));
        report.put("trace", List.copyOf(trace));
        report.put("threatIntelProvider", intelligence.provider());
        report.put("threatIntelSourceCount", intelligence.sources().size());
        report.put("retrievalAffectedScore", false);
        report.put("durationMilliseconds", Duration.ofNanos(System.nanoTime() - started).toMillis());
        report.put("limitations", List.of(
                "第一版不下载目标页面正文，也不执行脚本，因此不会判断页面视觉仿冒、登录表单或重定向链",
                "公开网页搜索结果仅作为调查线索，不会自动证明域名恶意或直接改变风险分",
                "风险分衡量当前可见攻击面信号，不等同于恶意定性，最终结果需要人工核验"));

        return new Result(answer(target, riskScore, riskLevel, signals, tls, intelligence),
                Map.copyOf(report), intelligence);
    }

    private List<InetAddress> resolve(String host) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return List.of(InetAddress.getAllByName(host)); }
                catch (UnknownHostException exception) { throw new IllegalStateException(exception); }
            }).get(NETWORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessConflictException("WEB_SECURITY_DNS_INTERRUPTED", "DNS investigation was interrupted");
        } catch (Exception exception) {
            throw new BusinessConflictException("WEB_SECURITY_DNS_UNRESOLVED", "The target hostname could not be resolved");
        }
    }

    private Map<String, Object> inspectTls(String host, int port, List<InetAddress> addresses) {
        String lastFailure = "TLS handshake failed";
        for (InetAddress address : addresses) {
            try (java.net.Socket raw = new java.net.Socket()) {
                raw.connect(new InetSocketAddress(address, port), NETWORK_TIMEOUT_MILLIS);
                raw.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
                try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true)) {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!host.contains(":")) parameters.setServerNames(List.of(new SNIHostName(host)));
                    socket.setSSLParameters(parameters);
                    socket.startHandshake();
                    Certificate[] chain = socket.getSession().getPeerCertificates();
                    if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                        return Map.of("status", "UNAVAILABLE", "reason", "服务端没有返回可解析的 X.509 证书");
                    }
                    long daysRemaining = ChronoUnit.DAYS.between(Instant.now(), leaf.getNotAfter().toInstant());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "VERIFIED");
                    result.put("protocol", socket.getSession().getProtocol());
                    result.put("cipherSuite", socket.getSession().getCipherSuite());
                    result.put("subject", leaf.getSubjectX500Principal().getName());
                    result.put("issuer", leaf.getIssuerX500Principal().getName());
                    result.put("notBefore", leaf.getNotBefore().toInstant().toString());
                    result.put("notAfter", leaf.getNotAfter().toInstant().toString());
                    result.put("daysRemaining", daysRemaining);
                    result.put("chainLength", chain.length);
                    return Map.copyOf(result);
                }
            } catch (Exception exception) {
                lastFailure = compact(exception.getMessage());
            }
        }
        return Map.of("status", "UNAVAILABLE", "reason", lastFailure);
    }

    private List<Map<String, Object>> riskSignals(URI target, String host, Map<String, Object> tls) {
        List<Map<String, Object>> signals = new ArrayList<>();
        String combined = (host + " " + String.valueOf(target.getRawPath()) + " "
                + String.valueOf(target.getRawQuery())).toLowerCase(Locale.ROOT);
        if ("http".equalsIgnoreCase(target.getScheme())) {
            signals.add(signal("NO_TLS", "HIGH", 25, "目标使用明文 HTTP，传输内容和凭据可能被窃听或篡改"));
        } else if (!"VERIFIED".equals(tls.get("status"))) {
            signals.add(signal("TLS_UNVERIFIED", "MEDIUM", 15, "未能完成可信 TLS 与主机名校验"));
        }
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            signals.add(signal("PUNYCODE_HOST", "MEDIUM", 15, "域名包含 Punycode，需要人工检查是否为同形字仿冒"));
        }
        if (isIpLiteral(host)) {
            signals.add(signal("IP_LITERAL", "MEDIUM", 15, "URL 直接使用 IP 地址，缺少常规域名身份线索"));
        }
        if (host.chars().filter(character -> character == '.').count() >= 4) {
            signals.add(signal("DEEP_SUBDOMAIN", "LOW", 8, "域名层级较深，需确认实际注册域与展示品牌是否一致"));
        }
        if (combined.matches(".*(login|signin|verify|verification|account|wallet|bank|payment|password|reset|bonus|gift|客服|登录|验证|账户|钱包|付款|中奖).*")) {
            signals.add(signal("SOCIAL_ENGINEERING_TERMS", "MEDIUM", 12, "URL 中出现登录、验证、支付或诱导相关词汇"));
        }
        if (target.getRawQuery() != null && target.getRawQuery().matches("(?i).*(url|redirect|continue|next)=https?%?3?a.*")) {
            signals.add(signal("NESTED_REDIRECT_PARAMETER", "LOW", 8, "查询参数可能包含外部跳转目标"));
        }
        Object days = tls.get("daysRemaining");
        if (days instanceof Number number && number.longValue() < 14) {
            signals.add(signal("CERTIFICATE_EXPIRING", "LOW", 5, "TLS 证书将在两周内到期"));
        }
        return List.copyOf(signals);
    }

    private String answer(
            URI target, int riskScore, String riskLevel, List<Map<String, Object>> signals,
            Map<String, Object> tls, LiveWebSearchClient.SearchResponse intelligence) {
        StringBuilder result = new StringBuilder();
        result.append("## Web 安全调查初步结果\n\n")
                .append("- **目标**：`").append(target.toASCIIString()).append("`\n")
                .append("- **风险等级**：").append(levelLabel(riskLevel)).append("（")
                .append(riskScore).append("/100）\n")
                .append("- **TLS 状态**：").append(tlsSummary(tls)).append("\n")
                .append("- **公开威胁线索**：").append(intelligence.sources().size())
                .append(" 条；仅供人工核实，未直接改变风险分\n\n")
                .append("### 风险信号\n\n");
        if (signals.isEmpty()) result.append("当前受控检查没有发现明显的 URL、DNS 或 TLS 风险信号。\n");
        else for (Map<String, Object> signal : signals) {
            result.append("- ").append(signal.get("message")).append("（+")
                    .append(signal.get("points")).append("）\n");
        }
        result.append("\n### 结论边界\n\n")
                .append("这是防御性初筛，不是对网站恶意性的最终定性。第一版不会下载或执行目标页面内容，")
                .append("因此不分析页面仿冒、表单和脚本；请结合下方 IOC、证书信息与公开来源完成人工核验。");
        return result.toString();
    }

    private Map<String, Object> signal(String code, String severity, int points, String message) {
        return Map.of("code", code, "severity", severity, "points", points, "message", message);
    }

    private Map<String, Object> trace(String stage, String status, String summary) {
        return Map.of("stage", stage, "status", status, "summary", summary);
    }

    private String riskLevel(int score) {
        if (score >= 60) return "HIGH";
        if (score >= 30) return "MEDIUM";
        return "LOW";
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            default -> "低";
        };
    }

    private String tlsSummary(Map<String, Object> tls) {
        return switch (String.valueOf(tls.get("status"))) {
            case "VERIFIED" -> "可信 TLS 握手和主机名校验通过，证书剩余 "
                    + tls.getOrDefault("daysRemaining", "未知") + " 天";
            case "NOT_APPLICABLE" -> String.valueOf(tls.getOrDefault("reason", "目标未使用 TLS"));
            default -> "TLS 校验不可用：" + tls.getOrDefault("reason", "未知原因");
        };
    }

    private boolean isIpLiteral(String host) {
        if (host.contains(":")) return true;
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private String compact(String value) {
        String normalized = value == null ? "未知握手错误" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    public record Result(
            String answer, Map<String, Object> report, LiveWebSearchClient.SearchResponse intelligence) {}
}
