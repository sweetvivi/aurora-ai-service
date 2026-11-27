package com.hub.backend.common.logging.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.logging.SensitiveMasker;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 把「变更前」和「变更后」两个对象变成可落库的三段 JSON：before / after / changed。
 *
 * <p>三个决定它长什么样的取舍：</p>
 * <ol>
 *   <li><b>先脱敏再比较</b>。顺序反了会把明文密码留在内存里的中间 Map 中，日志层脱敏就白做了；
 *       而且 after/before 脱敏后比较，diff 里也不会出现「***→***」这种噪声。</li>
 *   <li><b>diff 只记变化字段</b>。整份快照可能几十个字段，事故复盘真正要看的往往就一两个。
 *       嵌套结构拍平成 {@code videoCapabilities.modes[0]} 这种路径，一条 SQL 就能按字段名查。</li>
 *   <li><b>不记 null 差异之外的「无变化」</b>。一次「保存」没实际改动任何字段时，
 *       changed 为空对象——这本身是有用信息（说明操作是幂等重复提交）。</li>
 * </ol>
 */
@Component
public class AuditSnapshots {

    /** 单次 diff 最多保留的变化条目。批量操作可能改上百行，全留会把 TEXT 撑爆且没人看。 */
    private static final int MAX_CHANGES = 100;

    private final ObjectMapper mapper;

    public AuditSnapshots(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 转 JSON 并脱敏。序列化失败不抛异常——审计写不出来不该让已经成功的业务变成 500，
     * 退化成一段标记文本即可。
     */
    public JsonNode toMaskedTree(Object value) {
        if (value == null) {
            return null;
        }
        Object payload = value instanceof ResponseEntity<?> response ? response.getBody() : value;
        if (payload == null) {
            return null;
        }
        try {
            return SensitiveMasker.maskTree(mapper.valueToTree(payload));
        } catch (RuntimeException ex) {
            return mapper.getNodeFactory().textNode("***unserializable:" + ex.getClass().getSimpleName() + "***");
        }
    }

    /** 序列化成落库字符串，超长截断。 */
    public String toJson(Object value, int maxLength) {
        JsonNode node = value instanceof JsonNode json ? json : toMaskedTree(value);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return truncate(node.toString(), maxLength);
    }

    /**
     * 字段级 diff：{@code {"visibility":{"from":"ACTIVE","to":"DISABLED"}}}。
     *
     * @return 无变化时返回空对象字符串 {@code "{}"}；任一侧为空时返回 {@code null}
     *         （「新增」或「纯删除」没有可比对象，看 after/before 原样即可）
     */
    public String diff(JsonNode before, JsonNode after, int maxLength) {
        if (before == null || after == null) {
            return null;
        }
        Map<String, JsonNode> flatBefore = flatten(before);
        Map<String, JsonNode> flatAfter = flatten(after);
        // TreeSet 保证同一份数据每次生成的 JSON 顺序一致，diff 结果可比较、可 diff。
        TreeSet<String> paths = new TreeSet<>(flatBefore.keySet());
        paths.addAll(flatAfter.keySet());

        ObjectNode changed = mapper.createObjectNode();
        int count = 0;
        for (String path : paths) {
            if (count >= MAX_CHANGES) {
                changed.put("__truncated__", true);
                break;
            }
            JsonNode from = flatBefore.get(path);
            JsonNode to = flatAfter.get(path);
            if (sameValue(from, to)) {
                continue;
            }
            ObjectNode entry = changed.putObject(path);
            entry.set("from", from == null ? mapper.nullNode() : from);
            entry.set("to", to == null ? mapper.nullNode() : to);
            count++;
        }
        return truncate(changed.toString(), maxLength);
    }

    /** 拍平成 {@code path → 叶子值}，数组下标进路径（{@code items[0].enabled}）。 */
    private Map<String, JsonNode> flatten(JsonNode node) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        flattenInto("", node, out);
        return out;
    }

    private void flattenInto(String prefix, JsonNode node, Map<String, JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> field = it.next();
                flattenInto(prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(),
                        field.getValue(), out);
            }
            return;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                flattenInto(prefix + "[" + i + "]", arr.get(i), out);
            }
            // 空数组本身也要参与比较，否则「清空列表」这个变更会被漏掉。
            if (arr.isEmpty()) {
                out.put(prefix, node);
            }
            return;
        }
        out.put(prefix.isEmpty() ? "$" : prefix, node);
    }

    private static boolean sameValue(JsonNode a, JsonNode b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /** 落库前统一截断，保留可辨认的前缀并标注被截断。 */
    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…(truncated)";
    }
}
