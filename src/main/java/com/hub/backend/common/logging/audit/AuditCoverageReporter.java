package com.hub.backend.common.logging.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动时扫描「管理端写接口里没有 {@code @AuditLog} 的那些」，打 WARN 并逐条列出。
 *
 * <p>这是注解方案唯一的薄弱点：漏加注解的接口不会报错，只会安静地没有审计。
 * 光靠代码评审总会漏——新加一个 {@code @PostMapping} 时，没人会记得还有审计这回事。
 * 启动扫描把这件事变成**启动日志里可见的清单**，配合 CI 抓 WARN 就能拦住。</p>
 *
 * <p>只扫 {@code /api/admin} 前缀：前台接口的写操作（发消息、兑换）多数是用户对自己数据的高频操作，
 * 全量审计会淹没真正的管理动作；需要审计的已在业务注解上显式标注。</p>
 */
@Component
public class AuditCoverageReporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(AuditCoverageReporter.class);

    private static final String ADMIN_PREFIX = "/api/admin";

    private final RequestMappingHandlerMapping handlerMapping;
    private final AuditLogProperties properties;

    public AuditCoverageReporter(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            AuditLogProperties properties) {
        this.handlerMapping = handlerMapping;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.isEnabled() || !properties.isWarnMissingAnnotations()) {
            return;
        }
        Set<String> missing = new TreeSet<>();
        int audited = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (!isAdminWrite(info)) {
                continue;
            }
            boolean covered = AnnotatedElementUtils.findMergedAnnotation(
                    handler.getMethod(), AuditLog.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(
                    handler.getBeanType(), AuditLog.class) != null;
            if (covered) {
                audited++;
                continue;
            }
            String method = info.getMethodsCondition().getMethods().stream()
                    .findFirst().map(RequestMethod::name).orElse("ANY");
            for (String pattern : info.getPatternValues()) {
                missing.add(method + " " + pattern);
            }
        }

        if (missing.isEmpty()) {
            log.info("Audit coverage: {} admin write endpoints, all annotated", audited);
            return;
        }
        // 一条 WARN 汇总 + 逐行明细：汇总便于告警规则匹配，明细便于直接复制去补注解。
        log.warn("Audit coverage: {} admin write endpoints lack @AuditLog ({} annotated)",
                missing.size(), audited);
        for (String item : new ArrayList<>(missing)) {
            log.warn("Audit coverage missing: {}", item);
        }
    }

    private boolean isAdminWrite(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (!methods.isEmpty() && methods.stream().allMatch(this::isSafe)) {
            return false;
        }
        for (String pattern : info.getPatternValues()) {
            if (pattern.startsWith(ADMIN_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafe(RequestMethod method) {
        return method == RequestMethod.GET || method == RequestMethod.HEAD || method == RequestMethod.OPTIONS;
    }
}
