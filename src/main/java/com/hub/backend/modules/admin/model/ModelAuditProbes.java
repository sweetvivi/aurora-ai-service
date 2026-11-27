package com.hub.backend.modules.admin.model;

import org.springframework.stereotype.Component;

/**
 * 模型管理审计的「变更前」观测点。
 *
 * <p>为什么需要它：{@code @AuditLog} 的默认 before 只能拿到方法入参（一个「要改成什么」的请求体），
 * 拿不到「现在是什么」。对「模型上下架」这种操作，入参只说明目标状态，
 * 真正要追溯的是<b>原来那一行是什么样</b>——上架前是隐藏还是下线、价格组有没有动过。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * @AuditLog(action = "UPDATE_MODEL_VISIBILITY", target = "LLM_MODEL", id = "#id",
 *           before = "@modelAuditProbes.snapshot(#id)")
 * }</pre>
 *
 * <p>返回的是 {@code AdminModelResponse}（列表/详情接口的同一份结构），所以 after 直接用方法返回值，
 * 两边字段完全对齐，diff 出来就是真正的「字段级变化」而不是两套结构的差异。</p>
 */
@Component("modelAuditProbes")
public class ModelAuditProbes {

    private final LlmModelAdminService modelAdminService;

    public ModelAuditProbes(LlmModelAdminService modelAdminService) {
        this.modelAdminService = modelAdminService;
    }

    /**
     * 读取模型当前状态。不存在时返回 {@code null} 而不是抛异常：
     * 审计切面在业务执行前调用，一个「删除不存在的 id」的请求本来就该由业务返回 404，
     * 不能因为审计取不到快照就提前把请求打回。
     */
    public Object snapshot(Long id) {
        if (id == null) {
            return null;
        }
        try {
            return modelAdminService.detail(id);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
