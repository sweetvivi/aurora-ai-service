package com.hub.backend.modules.ppt;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps product-facing PPT options onto ppt-master generate fields.
 */
final class PptRequestMapper {

    private static final Pattern NAMED_SUBJECT = Pattern.compile(
            "(?:主题|产品名称|项目名称|课程名称)\\s*(?:为|是)?\\s*[:：]?\\s*[\\\"“「]?([^\\\"”」\\r\\n，。,：:]{2,80})");

    private PptRequestMapper() {
    }

    static Integer resolveSlideCount(String pageRange) {
        if (!StringUtils.hasText(pageRange)) {
            return 12;
        }
        String normalized = pageRange.trim();
        return switch (normalized) {
            case "10-15" -> 12;
            case "20-30" -> 24;
            case "30-50" -> 36;
            default -> {
                int dash = normalized.indexOf('-');
                if (dash > 0) {
                    try {
                        int low = Integer.parseInt(normalized.substring(0, dash).trim());
                        int high = Integer.parseInt(normalized.substring(dash + 1).trim());
                        yield Math.max(3, Math.min(50, (low + high) / 2));
                    } catch (NumberFormatException ignored) {
                        yield 12;
                    }
                }
                try {
                    yield Math.max(3, Math.min(50, Integer.parseInt(normalized)));
                } catch (NumberFormatException ignored) {
                    yield 12;
                }
            }
        };
    }

    static String mapTone(String tone) {
        if (!StringUtils.hasText(tone)) {
            return "default";
        }
        String t = tone.trim();
        return switch (t) {
            case "专业", "正式", "professional" -> "professional";
            case "活泼", "casual", "funny" -> "casual";
            case "教学", "educational" -> "educational";
            case "销售", "sales_pitch" -> "sales_pitch";
            default -> "default";
        };
    }

    static String mapLanguage(String lang) {
        if (!StringUtils.hasText(lang)) {
            return "Chinese";
        }
        String value = lang.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("en")) {
            return "English";
        }
        if (value.startsWith("zh") || value.contains("中文") || "cn".equals(value)) {
            return "Chinese";
        }
        return lang.trim();
    }

    static String buildContent(String topic, String sourceText, String extraFromFiles) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(topic)) {
            sb.append(topic.trim());
        }
        if (StringUtils.hasText(sourceText)) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("参考素材：\n").append(sourceText.trim());
        }
        if (StringUtils.hasText(extraFromFiles)) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(extraFromFiles.trim());
        }
        return sb.toString();
    }

    static String safeTitle(String topic, String fallback) {
        if (StringUtils.hasText(topic)) {
            String normalized = topic.trim().replaceAll("\\s+", " ");
            Matcher named = NAMED_SUBJECT.matcher(normalized);
            if (named.find()) {
                return named.group(1).trim();
            }
            String firstLine = topic.trim().split("\\R", 2)[0].trim();
            firstLine = firstLine.replaceFirst("^(?:请制作|请生成|制作|生成)\\s*", "");
            if (firstLine.length() > 80) {
                firstLine = firstLine.substring(0, 80).trim();
            }
            return StringUtils.hasText(firstLine) ? firstLine : fallback;
        }
        return fallback;
    }
}
