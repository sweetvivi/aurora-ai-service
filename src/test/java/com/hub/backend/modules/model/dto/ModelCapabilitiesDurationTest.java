package com.hub.backend.modules.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelCapabilitiesDurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rangeModeIsExpandedForClients() {
        // 上游对 4–15 这类模型逐秒都收，目录要如实给出每一个，否则下拉只有 5/10/15 三个值，
        // 用户就以为 7 秒不行 —— 这正是「1-14 秒到底能不能生成」的疑问来源。
        ModelCapabilities declared = ModelCapabilities.parse(
                "{\"durations\":[4,15],\"durationMode\":\"range\"}", objectMapper);

        List<Integer> options = declared.published().durationOptions();
        assertEquals(12, options.size());
        assertEquals(List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), options);
    }

    @Test
    void setModeIsPublishedAsIs() {
        ModelCapabilities declared = ModelCapabilities.parse(
                "{\"durations\":[10,15],\"durationMode\":\"set\"}", objectMapper);

        assertEquals(List.of(10, 15), declared.published().durationOptions());
    }

    @Test
    void missingModeKeepsTheHistoricalRangeReading() {
        // 老数据没有 durationMode，此前一直被当作闭区间校验，这里不能改成 set 语义。
        ModelCapabilities declared = ModelCapabilities.parse("{\"durations\":[5,10]}", objectMapper);

        assertNull(declared.durationMode());
        assertEquals(List.of(5, 6, 7, 8, 9, 10), declared.durationOptions());
    }

    @Test
    void groupNarrowsDurationsToTheIntersectionOfItsMembers() {
        // 组里多数成员收 4–15 任意整数，但 fast-GT 只做 10/15。组对外只能承诺两者都认的那几档，
        // 否则前台给出 7 秒，请求落到 fast-GT 就会被上游拒。
        ModelCapabilities cheapest = ModelCapabilities.parse(
                "{\"durations\":[4,15],\"durationMode\":\"range\"}", objectMapper);
        ModelCapabilities strict = ModelCapabilities.parse(
                "{\"durations\":[10,15],\"durationMode\":\"set\"}", objectMapper);

        ModelCapabilities grouped = cheapest.narrowedWith(List.of(strict));

        assertEquals(List.of(10, 15), grouped.durationOptions());
        assertEquals(ModelCapabilities.DURATION_MODE_SET, grouped.durationMode());
    }

    @Test
    void membersWithoutDurationsDoNotShrinkTheGroup() {
        ModelCapabilities range = ModelCapabilities.parse(
                "{\"durations\":[4,15],\"durationMode\":\"range\"}", objectMapper);
        ModelCapabilities silence = ModelCapabilities.parse(
                "{\"modes\":[\"text2video\"]}", objectMapper);

        assertEquals(12, range.narrowedWith(
                java.util.Arrays.asList(silence, null)).durationOptions().size());
    }

    @Test
    void disjointMembersKeepTheRepresentativeDeclaration() {
        // 交集为空时无解：宁可沿用代表成员的声明（前端/后端仍会按各自的 set/range 校验），
        // 也不要发布一个空列表让页面看着像「什么都不能选」。
        ModelCapabilities a = ModelCapabilities.parse(
                "{\"durations\":[5],\"durationMode\":\"set\"}", objectMapper);
        ModelCapabilities b = ModelCapabilities.parse(
                "{\"durations\":[10],\"durationMode\":\"set\"}", objectMapper);

        assertEquals(List.of(5), a.narrowedWith(List.of(b)).durationOptions());
    }

    @Test
    void groupAlsoNarrowsResolutionsAndRatios() {
        // capella 只做 720p、只收 16:9/9:16，而组里其它成员 480p/720p、画幅更多。
        // 前台只该看到两边都认的那部分，否则请求落到 capella 会被上游 400。
        ModelCapabilities loose = ModelCapabilities.parse(
                "{\"durations\":[4,15],\"durationMode\":\"range\","
                        + "\"resolutions\":[\"480p\",\"720p\"],"
                        + "\"aspectRatios\":[\"16:9\",\"9:16\",\"1:1\",\"21:9\"]}",
                objectMapper);
        ModelCapabilities strict = ModelCapabilities.parse(
                "{\"durations\":[5,15],\"durationMode\":\"range\","
                        + "\"resolutions\":[\"720p\"],"
                        + "\"aspectRatios\":[\"16:9\",\"9:16\"]}",
                objectMapper);

        ModelCapabilities grouped = loose.narrowedWith(List.of(strict));

        assertEquals(List.of("720p"), grouped.resolutions());
        assertEquals(List.of("16:9", "9:16"), grouped.aspectRatios());
        assertEquals(11, grouped.durationOptions().size());
    }

    @Test
    void operatorOverrideReplacesOnlyTheDurations() {
        // 运营在组上写死「4–15 区间」后，目录就该按这个对外声明，哪怕组里还躺着只认 10/15 的成员；
        // 其余能力（模式、画幅、分辨率、参考图上限）仍沿用成员声明。
        ModelCapabilities member = ModelCapabilities.parse(
                "{\"modes\":[\"text2video\",\"image2video\"],\"aspectRatios\":[\"16:9\"],"
                        + "\"resolutions\":[\"720p\"],\"durations\":[10,15],"
                        + "\"durationMode\":\"set\",\"maxReferenceImages\":4,\"audio\":false}",
                objectMapper);

        ModelCapabilities overridden = member.withDurations(List.of(4, 15), "range").published();

        assertEquals(12, overridden.durationOptions().size());
        assertEquals(List.of(4, 15), member.withDurations(List.of(4, 15), "range").durations());
        assertEquals(List.of("16:9"), overridden.aspectRatios());
        assertEquals(List.of("720p"), overridden.resolutions());
        assertEquals(4, overridden.maxReferenceImages());
    }

    @Test
    void unknownOverrideModeFallsBackToRange() {
        ModelCapabilities member = ModelCapabilities.parse(
                "{\"durations\":[10,15]}", objectMapper);

        ModelCapabilities overridden = member.withDurations(List.of(5, 8), null);

        assertEquals(ModelCapabilities.DURATION_MODE_RANGE, overridden.durationMode());
        assertEquals(List.of(5, 6, 7, 8), overridden.durationOptions());
    }

    @Test
    void publicationsWithoutDurationsAreLeftAlone() {
        ModelCapabilities declared = ModelCapabilities.parse("{\"modes\":[\"text2video\"]}", objectMapper);

        assertEquals(List.of(), declared.durationOptions());
        assertEquals(declared, declared.published());
    }
}
