package com.hub.backend.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把落库前的请求参数与错误明细里的敏感信息遮掉。
 *
 * <p><b>为什么是三道而不是一道：</b></p>
 * <ol>
 *   <li>字段名黑名单——{@code password}、{@code accessToken} 这类一眼能认出来的，整字段遮掉；</li>
 *   <li>值形态兜底——{@code content} 这种普通字段里，用户完全可能自己输入手机号、邮箱、
 *       甚至把 token 贴在聊天内容里。字段名永远认不出来，只有正则能兜；</li>
 *   <li>长度与深度限制——防超大报文和深层嵌套把脱敏本身变成 CPU 热点。</li>
 * </ol>
 *
 * <p><b>打码而不是删除，是因为删掉就失去了排查价值：</b>客服说「用户 138****5678 反馈画图失败」，
 * 你能直接搜到这个号落地的是哪个 id；全删了就只能靠时间窗撞运气。同理 JWT 留前 8 位，
 * 足够对上「哪把 key」，又不足以拿去重放。</p>
 *
 * <p>本类无状态、线程安全；所有方法在业务线程执行，保证进入队列的已经是脱敏后的内容，
 * 原始明文不会在内存里二次留存。</p>
 */
public final class SensitiveMasker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 命中即整字段替换为 {@link #FULL_MASK}，不保留长度（长度本身也是信息）。
     *
     * <p>密码类字段要按「所有叫得出名字的变体」逐个列：这行黑名单漏一个，
     * 漏掉的那个就是明文落库。{@code currentPassword} 曾经就是这样漏掉的——
     * 只写了 old/new/confirm 三个变体，改密码接口的当前密码一直以明文进表。</p>
     *
     * <p>兑换码（{@code code}）等同现金：谁在日志里读到它就能替用户把额度领走。
     * 只精确匹配字段名，{@code errorCode}、{@code modelCode} 这类不影响。</p>
     */
    private static final Set<String> DENY_FIELDS = Set.of(
            "password", "passwd", "pwd", "oldpassword", "newpassword", "confirmpassword",
            "currentpassword", "repeatpassword", "verifypassword",
            "token", "accesstoken", "refreshtoken", "idtoken", "relaykey", "apikey", "secret",
            "clientsecret", "authorization", "cookie", "setcookie", "sessionid", "jsessionid",
            "captcha", "captchacode", "verifycode", "smscode", "confirmation",
            "code", "redeemcode", "redemptioncode", "invitecode", "promocode", "couponcode",
            "privatekey", "signature", "credential", "credentials");

    private static final String FULL_MASK = "***";

    /** JSON 递归深度上限。正常业务报文远达不到，超过的只可能是恶意构造或序列化 bug。 */
    private static final int MAX_DEPTH = 8;

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9_\\-.]{8,}");
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]*(@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[\\dXx])(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");

    private SensitiveMasker() {
    }

    /**
     * 脱敏一段 JSON 文本；解析失败时退化为正则兜底，仍要返回可落库的内容而不抛异常。
     *
     * @param raw    原始报文，可为 {@code null}
     * @param maxLen 落库前的最大长度，超出部分丢弃并标注
     */
    public static String maskJson(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String masked;
        try {
            JsonNode root = MAPPER.readTree(raw);
            masked = maskNode(root, 0).toString();
        } catch (Exception ex) {
            // 非法 JSON（或者根本不是 JSON）不能让整条日志丢掉：退化为文本兜底。
            masked = maskText(raw);
        }
        return truncate(masked, maxLen);
    }

    /** 脱敏查询串，形如 {@code page=1&password=xx}。 */
    public static String maskQuery(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLen + 16));
        for (String pair : raw.split("&")) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                sb.append(pair);
                continue;
            }
            String key = pair.substring(0, eq);
            sb.append(key).append('=');
            if (isDeniedField(key)) {
                sb.append(FULL_MASK);
            } else {
                sb.append(maskText(safeDecode(pair.substring(eq + 1))));
            }
        }
        return truncate(sb.toString(), maxLen);
    }

    /** 对任意文本做值形态兜底，保留结构、只遮敏感片段。 */
    public static String maskText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String out = raw;
        // lambda 版 replaceAll 不解析 $ 引用，前 8 位里带特殊字符也不会出错。
        out = JWT.matcher(out).replaceAll(m -> m.group().substring(0, 8) + FULL_MASK);
        out = BEARER.matcher(out).replaceAll("Bearer " + FULL_MASK);
        // $1 / $2 保留首尾：手机号留前 3 后 4，邮箱留首字符和域名，证件号留前 6 后 4。
        out = PHONE_CN.matcher(out).replaceAll("$1****$2");
        out = EMAIL.matcher(out).replaceAll("$1***$2");
        out = ID_CARD.matcher(out).replaceAll("$1********$2");
        out = BANK_CARD.matcher(out).replaceAll("$1********$2");
        return out;
    }

    /** 判断字段名是否属于「整字段不可落库」。大小写与下划线/连字符都不敏感。 */
    public static boolean isDeniedField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return DENY_FIELDS.contains(normalized);
    }

    /**
     * 对已解析的 JSON 树递归脱敏，返回同一个树（原地修改）。
     *
     * <p>给审计快照用：调用方已经用带 JavaTime 模块的 ObjectMapper 把实体/返回值转成了树，
     * 这里只负责遮字段与值形态，不重复做序列化——审计的对象常常是实体，用本类的静态
     * MAPPER 去转会因为缺少时间类型模块直接抛异常。</p>
     */
    public static JsonNode maskTree(JsonNode node) {
        return maskNode(node, 0);
    }

    private static JsonNode maskNode(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (depth > MAX_DEPTH) {
            return TextNode.valueOf("***max-depth***");
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // 先收集键名再改值：迭代中修改同一个 ObjectNode 会抛 ConcurrentModificationException。
            List<String> keys = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                keys.add(it.next().getKey());
            }
            for (String key : keys) {
                if (isDeniedField(key)) {
                    obj.put(key, FULL_MASK);
                } else {
                    obj.set(key, maskNode(obj.get(key), depth + 1));
                }
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, maskNode(arr.get(i), depth + 1));
            }
            return arr;
        }
        if (node.isTextual()) {
            String masked = maskText(node.asText());
            // 值形态命中（手机号/JWT 等）时换成脱敏后的字符串，没命中则原样返回。
            return masked.equals(node.asText()) ? node : TextNode.valueOf(masked);
        }
        return node;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || maxLen <= 0 || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "…(truncated)";
    }

    private static String safeDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }
}
