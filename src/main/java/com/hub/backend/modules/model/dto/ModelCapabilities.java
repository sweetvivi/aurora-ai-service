package com.hub.backend.modules.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Declared capabilities of one model, as the catalogue publishes them.
 *
 * <p>Null on the response means "not declared" — the client then applies its own defaults. That is
 * deliberately different from an empty object: an empty list would say the model accepts nothing.</p>
 *
 * @param modes              generation modes the model supports, e.g. {@code text2video},
 *                           {@code image2video}, {@code first-last-frame}, {@code reference2video}
 * @param aspectRatios       accepted aspect ratios
 * @param resolutions        accepted resolution labels
 * @param durations          accepted clip lengths in seconds; see {@code durationMode}
 * @param durationMode       how {@code durations} is read: {@code range} means the two values are
 *                           the inclusive bounds and <em>every</em> integer between them is legal,
 *                           {@code set} means only the listed values are. {@code null} keeps the
 *                           historical reading, which was also {@code range} — so old rows and old
 *                           clients are unaffected
 * @param maxReferenceImages how many reference images may be attached
 * @param audio              whether the output carries generated audio
 * @param thinkingParameter  optional OpenAI-compatible request field name to enable model reasoning
 * @param thinkingOnValue    value written when thinking is on; blank defaults to boolean {@code true}
 * @param thinkingOffValue   value written when thinking is off; blank defaults to boolean {@code false}
 */
@Schema(name = "ModelCapabilities", description = "模型能力声明；null 表示未声明")
public record ModelCapabilities(
        @Schema(description = "支持的生成模式") List<String> modes,
        @Schema(description = "支持的画面比例") List<String> aspectRatios,
        @Schema(description = "支持的分辨率档位") List<String> resolutions,
        @Schema(description = "支持的时长（秒）；durationMode=range 时是闭区间上下限，set 时是可选档位")
        List<Integer> durations,
        @Schema(description = "时长读法：range（区间，每个整数都合法）/ set（只认列出的档位）；"
                + "null 等同 range，兼容历史数据", example = "range")
        String durationMode,
        @Schema(description = "最多参考图数量") int maxReferenceImages,
        @Schema(description = "是否输出音频") boolean audio,
        @Schema(description = "开启思考模式的 OpenAI-compatible 请求字段名，例如 enable_thinking")
        String thinkingParameter,
        @Schema(description = "开启思考时写入上游的值；未配置时默认 true。DeepSeek 填 true，GPT 可填 medium")
        String thinkingOnValue,
        @Schema(description = "关闭思考时写入上游的值；未配置时默认 false。DeepSeek 填 false，GPT 可填 none")
        String thinkingOffValue
) {

    /** Inclusive bounds; every integer between them is legal. The default when undeclared. */
    public static final String DURATION_MODE_RANGE = "range";

    /** Only the listed seconds are legal — upstream rejects anything in between. */
    public static final String DURATION_MODE_SET = "set";

    /** @return true when this declaration accepts {@code seconds} as a clip length */
    public boolean allowsDuration(int seconds) {
        List<Integer> declared = durations == null ? List.of() : durations;
        if (declared.isEmpty()) {
            return true;
        }
        if (DURATION_MODE_SET.equalsIgnoreCase(durationMode == null ? "" : durationMode.strip())) {
            return declared.contains(seconds);
        }
        int min = declared.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int max = declared.stream().mapToInt(Integer::intValue).max().orElseThrow();
        return seconds >= min && seconds <= max;
    }

    /**
     * The selectable lengths, already normalised to what the catalogue should publish.
     *
     * <p>A range is expanded to every integer so the client does not have to know the rule; that
     * also keeps the dock honest about 7 秒 or 12 秒 being legal. A set is returned as declared.</p>
     *
     * @return options in ascending order, empty when nothing was declared
     */
    public List<Integer> durationOptions() {
        List<Integer> declared = durations == null ? List.of() : durations;
        if (declared.isEmpty()) {
            return List.of();
        }
        List<Integer> sorted = declared.stream().distinct().sorted().toList();
        if (DURATION_MODE_SET.equalsIgnoreCase(durationMode == null ? "" : durationMode.strip())) {
            return sorted;
        }
        int min = sorted.get(0);
        int max = sorted.get(sorted.size() - 1);
        List<Integer> expanded = new ArrayList<>(max - min + 1);
        for (int second = min; second <= max; second++) {
            expanded.add(second);
        }
        return List.copyOf(expanded);
    }

    /**
     * Replaces only the duration declaration, keeping every other capability.
     *
     * <p>A group's operator override goes through here: the group still advertises its members'
     * modes, ratios, resolutions and reference limit, but states the clip lengths the operator
     * intends instead of what the members happen to agree on.</p>
     *
     * @param options the lengths to publish; they are published as given
     * @param mode    {@code range} or {@code set}; anything else is treated as {@code range}
     * @return a copy carrying the new durations
     */
    public ModelCapabilities withDurations(List<Integer> options, String mode) {
        String normalized = DURATION_MODE_SET.equalsIgnoreCase(mode == null ? "" : mode.strip())
                ? DURATION_MODE_SET
                : DURATION_MODE_RANGE;
        return new ModelCapabilities(modes, aspectRatios, resolutions,
                options == null ? List.of() : List.copyOf(options), normalized,
                maxReferenceImages, audio, thinkingParameter, thinkingOnValue, thinkingOffValue);
    }

    /**
     * The document to hand to clients: a range is expanded into concrete seconds so the dock can
     * offer every legal length without knowing the rule.
     *
     * @return this instance when there is nothing to expand
     */
    public ModelCapabilities published() {
        List<Integer> options = durationOptions();
        if (options.isEmpty() || options.equals(durations)) {
            return this;
        }
        return new ModelCapabilities(modes, aspectRatios, resolutions, options, durationMode,
                maxReferenceImages, audio, thinkingParameter, thinkingOnValue, thinkingOffValue);
    }

    /**
     * The capabilities a fallback group can honestly advertise.
     *
     * <p>A group's members are interchangeable to the caller but <em>not</em> identical: on the
     * seedance 2.0 group, most members take any 4–15 秒 while {@code fast-GT} only takes 10 or 15.
     * Publishing the cheapest member's declaration would offer 7 秒 and then have the request
     * land on a member that rejects it. Publishing the strictest one would hide legal lengths.</p>
     *
     * <p>So durations become the <b>intersection</b>: only the lengths every member accepts. It is
     * emitted as a {@code set} because an intersection need not be contiguous. When any member
     * declares nothing, nothing is known and the representative's declaration is kept as-is.</p>
     *
     * @param others the other members' declarations, may contain {@code null}
     * @return this instance when nothing narrower can be stated
     */
    public ModelCapabilities narrowedWith(List<ModelCapabilities> others) {
        if (others == null || others.isEmpty()) {
            return published();
        }
        List<ModelCapabilities> members = new ArrayList<>();
        members.add(this);
        for (ModelCapabilities other : others) {
            if (other != null) {
                members.add(other);
            }
        }
        List<Integer> durations = intersectDurations(members);
        List<String> resolutions = intersectStrings(members, ModelCapabilities::resolutions);
        List<String> ratios = intersectStrings(members, ModelCapabilities::aspectRatios);
        return new ModelCapabilities(
                modes,
                ratios.isEmpty() ? aspectRatios : ratios,
                resolutions.isEmpty() ? this.resolutions : resolutions,
                durations.isEmpty() ? this.durations : durations,
                durations.isEmpty() || durations.equals(this.durations)
                        ? durationMode
                        : DURATION_MODE_SET,
                maxReferenceImages, audio,
                thinkingParameter, thinkingOnValue, thinkingOffValue);
    }

    /**
     * @return the lengths every member accepts, or an empty list when nothing narrower can be said
     */
    private static List<Integer> intersectDurations(List<ModelCapabilities> members) {
        Set<Integer> intersection = null;
        for (ModelCapabilities member : members) {
            List<Integer> options = member.durationOptions();
            if (options.isEmpty()) {
                // 没声明时长 = 对时长不设限，不会让交集变小
                continue;
            }
            Set<Integer> own = new LinkedHashSet<>(options);
            if (intersection == null) {
                intersection = own;
            } else {
                intersection.retainAll(own);
            }
        }
        if (intersection == null || intersection.isEmpty()) {
            return List.of();
        }
        return intersection.stream().sorted().toList();
    }

    /**
     * 分辨率 / 画幅的交集。
     *
     * <p>成员之间这两维同样会不一致：seedance-2.0 里多数成员 480p/720p 都能出，而 capella 只做
     * 720p。只要有一个成员不认，前台就不该把它列出来，否则请求落到那个成员就被上游 400。</p>
     *
     * @return the shared values in the first member's order, or an empty list when there is no
     *         intersection (the caller then keeps the representative's list)
     */
    private static List<String> intersectStrings(List<ModelCapabilities> members,
                                                 Function<ModelCapabilities, List<String>> pick) {
        List<String> ordered = null;
        Set<String> intersection = null;
        for (ModelCapabilities member : members) {
            List<String> values = pick.apply(member);
            if (values == null || values.isEmpty()) {
                // 没声明 = 不设限
                continue;
            }
            if (ordered == null) {
                ordered = values;
                intersection = new LinkedHashSet<>(values);
            } else {
                intersection.retainAll(values);
            }
        }
        if (ordered == null || intersection == null || intersection.isEmpty()) {
            return List.of();
        }
        return ordered.stream().filter(intersection::contains).toList();
    }

    /**
     * Parses the stored JSON.
     *
     * @return the capabilities, or {@code null} when the document is absent or unusable — an
     *         unreadable declaration must not break the whole catalogue
     */
    public static ModelCapabilities parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            return new ModelCapabilities(
                    strings(root.path("modes")),
                    strings(root.path("aspectRatios")),
                    strings(root.path("resolutions")),
                    integers(root.path("durations")),
                    text(root, "durationMode"),
                    root.path("maxReferenceImages").asInt(0),
                    root.path("audio").asBoolean(false),
                    text(root, "thinkingParameter"),
                    text(root, "thinkingOnValue"),
                    text(root, "thinkingOffValue"));
        } catch (Exception exception) {
            return null;
        }
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isValueNode() && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private static List<Integer> integers(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isInt() || item.isLong()) {
                values.add(item.asInt());
            } else if (item.isTextual()) {
                try {
                    values.add(Integer.parseInt(item.asText().strip()));
                } catch (NumberFormatException ignored) {
                    // skip values that are not numbers
                }
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
