package com.hub.backend.common.logging.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.logging.SensitiveMasker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计快照与 diff 的行为锁定。
 *
 * <p>这几条断言对应的是「审计能不能用来追责」的底线：变化字段必须精确、不变字段不能出现、
 * 敏感字段不能进库。任何一条被改坏，审计表就退化成一份看不出改了什么的心跳日志。</p>
 */
class AuditSnapshotsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditSnapshots snapshots = new AuditSnapshots(mapper);

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void diffKeepsOnlyChangedField() throws Exception {
        JsonNode before = tree("{\"id\":13,\"modelName\":\"Grok\",\"status\":\"ACTIVE\"}");
        JsonNode after = tree("{\"id\":13,\"modelName\":\"Grok\",\"status\":\"DISABLED\"}");

        String changed = snapshots.diff(before, after, 8192);

        assertThat(changed).contains("\"status\"").contains("\"from\":\"ACTIVE\"").contains("\"to\":\"DISABLED\"");
        // 没变的字段不该出现在 diff 里，否则一次保存会把几十个字段都算成「变更」。
        assertThat(changed).doesNotContain("modelName").doesNotContain("\"id\"");
    }

    @Test
    void diffReportsEmptyObjectWhenNothingChanged() throws Exception {
        JsonNode before = tree("{\"status\":\"ACTIVE\"}");
        JsonNode after = tree("{\"status\":\"ACTIVE\"}");

        // "{}" 本身是有用信息：说明这次操作是幂等重复提交。
        assertThat(snapshots.diff(before, after, 8192)).isEqualTo("{}");
    }

    @Test
    void diffFlattensNestedFieldsIntoPaths() throws Exception {
        JsonNode before = tree("{\"videoCapabilities\":{\"modes\":[\"text\",\"image\"],\"seconds\":5}}");
        JsonNode after = tree("{\"videoCapabilities\":{\"modes\":[\"text\"],\"seconds\":10}}");

        String changed = snapshots.diff(before, after, 8192);

        assertThat(changed).contains("videoCapabilities.seconds");
        assertThat(changed).contains("videoCapabilities.modes[1]");
    }

    @Test
    void diffIsNullWhenOneSideMissing() throws Exception {
        JsonNode present = tree("{\"balance\":100}");
        // 新增或纯删除没有可比对象，这时看 before/after 原文即可，硬造 diff 只会误导。
        assertThat(snapshots.diff(null, present, 8192)).isNull();
        assertThat(snapshots.diff(present, null, 8192)).isNull();
    }

    @Test
    void snapshotMasksDeniedFields() throws Exception {
        JsonNode masked = snapshots.toMaskedTree(java.util.Map.of(
                "username", "qa_probe",
                "password", "Secret123!",
                "phone", "13812345678"));

        assertThat(masked.get("username").asText()).isEqualTo("qa_probe");
        assertThat(masked.get("password").asText()).isEqualTo("***");
        // 值形态兜底：手机号字段名不在黑名单里也要遮住中间四位。
        assertThat(masked.get("phone").asText()).isEqualTo("138****5678");
    }

    @Test
    void maskTreeAlsoCoversValuesInsidePlainTextField() throws Exception {
        JsonNode masked = SensitiveMasker.maskTree(tree("{\"content\":\"我的手机号是 13812345678\"}"));

        assertThat(masked.get("content").asText()).isEqualTo("我的手机号是 138****5678");
    }

    @Test
    void truncatesOversizedSnapshot() {
        String value = "x".repeat(100);

        String truncated = AuditSnapshots.truncate(value, 20);

        assertThat(truncated).startsWith("xxxxxxxxxxxxxxxxxxxx").contains("(truncated)");
    }

    @Test
    void derivesActionAndTargetFromMethodWhenAnnotationOmitsThem() throws Exception {
        Method method = SampleController.class.getMethod("batchDelete");

        // 类级注解只给 target，action 由方法名推导：batchDelete → BATCH_DELETE。
        assertThat(AuditLogAspect.toUpperSnake("batchDelete")).isEqualTo("BATCH_DELETE");
        assertThat(AuditLogAspect.resolveTarget(null, method)).isEqualTo("SAMPLE");
    }

    /** 模拟一个管理端控制器：类名去掉 Controller 后缀后就是默认 target。 */
    @AuditLog
    static class SampleController {
        public void batchDelete() {
        }
    }
}
