package com.hub.backend.infra.queue;

/**
 * 队列名常量。
 *
 * <p>放在 infra 而不是某个业务模块：同一个队列可能有多个生产方（文件、PPT、画布素材都要删 blob），
 * 让它们各自 import 另一个业务模块的常量，会把模块之间连成一张网。消费者按名字订阅，
 * 生产方只认名字。</p>
 */
public final class TaskQueues {

    /** 删除存储对象。payload 是 storage key；处理幂等（删不存在的 key 不算错）。 */
    public static final String STORAGE_DELETE = "storage.delete";

    private TaskQueues() {
    }
}
