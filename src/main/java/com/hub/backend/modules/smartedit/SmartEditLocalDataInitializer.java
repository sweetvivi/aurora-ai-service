package com.hub.backend.modules.smartedit;

import com.hub.backend.modules.smartedit.entity.SmartEditRecordEntity;
import com.hub.backend.modules.smartedit.repository.SmartEditRecordRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Makes the public smart-editing examples available in the local H2 profile.
 *
 * <p>Flyway seeds these records for MySQL, but local startup uses Hibernate schema creation with
 * Flyway disabled. The check keeps this idempotent and avoids duplicating the MySQL seed.</p>
 */
@Component
public class SmartEditLocalDataInitializer implements ApplicationRunner {

    private static final String EMPTY_CANVAS =
            "{\"images\":[],\"scale\":1,\"stagePos\":{\"x\":0,\"y\":0}}";

    private final SmartEditRecordRepository records;

    public SmartEditLocalDataInitializer(SmartEditRecordRepository records) {
        this.records = records;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (records.countByStatus(SmartEditRecordEntity.STATUS_DEMO) > 0) {
            return;
        }

        records.saveAll(List.of(
                new SmartEditRecordEntity(
                        null,
                        "商品图精修示例",
                        EMPTY_CANVAS,
                        """
                        [{"id":"demo-user-1","role":"user","content":"保留商品主体，背景换成干净的纯色背景。","timestamp":1730000000000}]
                        """.strip(),
                        "",
                        SmartEditRecordEntity.STATUS_DEMO),
                new SmartEditRecordEntity(
                        null,
                        "人像抠图示例",
                        EMPTY_CANVAS,
                        """
                        [{"id":"demo-user-2","role":"user","content":"去除背景并保留头发边缘细节。","timestamp":1730000000000}]
                        """.strip(),
                        "",
                        SmartEditRecordEntity.STATUS_DEMO)));
    }
}
