package com.hub.backend.modules.file;

import com.hub.backend.infra.queue.TaskHandler;
import com.hub.backend.infra.queue.TaskQueues;
import com.hub.backend.infra.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 真正去删磁盘上的文件。
 *
 * <p>以前这一步挂在事务的 {@code afterCommit} 里，失败只记一行日志：进程在提交之后、删盘之前
 * 退出，或者存储短暂不可用，这个对象就永远留在盘上，没有任何地方记得它该被删。改成本模块投递
 * 一条队列消息（与删行同一个事务），由这里反复重试直到成功 —— 失败会留在 {@code task_queue}
 * 的 {@code last_error} 里，不再是日志里的一句「best effort」。</p>
 *
 * <p>幂等：{@code FileStorage#delete} 对不存在的 key 不报错，因此重复投递无害。</p>
 */
@Component
public class StorageCleanupHandler implements TaskHandler {

    /**
     * 队列名。发布方（文件 / PPT / 画布素材）都引用 {@link TaskQueues#STORAGE_DELETE}，
     * 这里保留别名只是为了让本模块的调用点读起来更直接。
     */
    public static final String QUEUE = TaskQueues.STORAGE_DELETE;

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupHandler.class);

    private final FileStorage storage;

    public StorageCleanupHandler(FileStorage storage) {
        this.storage = storage;
    }

    @Override
    public String queueName() {
        return QUEUE;
    }

    @Override
    public void handle(String payload) {
        if (!StringUtils.hasText(payload)) {
            // 空 key 没有任何可删的东西，重试也不会变好：当作成功，别占着重试额度。
            log.warn("Storage cleanup task carries an empty key; skipped");
            return;
        }
        storage.delete(payload);
        log.debug("Storage object {} removed", payload);
    }
}
