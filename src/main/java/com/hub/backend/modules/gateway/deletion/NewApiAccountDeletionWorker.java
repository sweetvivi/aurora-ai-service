package com.hub.backend.modules.gateway.deletion;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.modules.gateway.entity.NewApiDeletionTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把「注销时被限流挡住的远端删除」继续做完。
 *
 * <p>注销请求本身不等上游：本地账号该删就删，远端那一步失败就落成一条待办（见
 * {@link NewApiAccountDeletionQueue}）。这里每 30 秒扫一次，到点了就去删。
 * 用定时扫描而不是在请求线程里 sleep 重试，理由和前台的视频轮询一样：一次请求不该被
 * 上游的限流窗口绑住 20 分钟，进程重启也不该把待办丢掉。</p>
 *
 * <p>重放沿用入队时记下的那条路径（{@code ADMIN} 或 {@code SELF}），不擅自换招：
 * 换路径意味着多打一次 {@code /api/verify}，而那正是被限流的接口。</p>
 */
@Component
public class NewApiAccountDeletionWorker {

    private static final Logger log = LoggerFactory.getLogger(NewApiAccountDeletionWorker.class);

    private final NewApiAccountDeletionQueue queue;
    private final NewApiManagementClient newApi;
    private final NewApiProperties properties;

    public NewApiAccountDeletionWorker(NewApiAccountDeletionQueue queue,
                                       NewApiManagementClient newApi,
                                       NewApiProperties properties) {
        this.queue = queue;
        this.newApi = newApi;
        this.properties = properties;
    }

    /**
     * 一轮扫描。
     *
     * <p>首轮也延迟一个周期：启动瞬间去查没有意义（那时不会有待办），而应用刚起来时 New API
     * 常常正被目录同步、账号初始化这些启动流程占着配额。固定延迟而非固定频率：一轮里每个账号
     * 都要打两次上游，上一轮慢的时候不该让下一轮叠上来。</p>
     */
    @Scheduled(
            initialDelayString = "${app.new-api.deletion-sweep-interval-millis:30000}",
            fixedDelayString = "${app.new-api.deletion-sweep-interval-millis:30000}")
    public void sweep() {
        if (!properties.isEnabled() || !properties.isDeletionRetryEnabled()) {
            return;
        }
        List<NewApiDeletionTaskEntity> due = queue.claimDue();
        if (due.isEmpty()) {
            return;
        }
        log.info("Retrying {} queued New API account deletion(s)", due.size());
        for (NewApiDeletionTaskEntity task : due) {
            try {
                delete(task);
                queue.complete(task.getId());
                log.info("Queued New API account deletion for user {} succeeded (mode={}, "
                                + "attempts={})",
                        task.getLocalUserId(), task.getMode(), task.getAttempts() + 1);
            } catch (RuntimeException failure) {
                // 一条任务失败不能拖停整轮；下一次扫描还会再见到它（或已经是最后几次）。
                try {
                    queue.postpone(task.getId(), failure);
                } catch (RuntimeException bookkeeping) {
                    log.warn("Unable to reschedule the New API account deletion for user {}: {}",
                            task.getLocalUserId(), bookkeeping.getMessage());
                }
            }
        }
    }

    /** 按入队时选定的路径删一次。失败直接抛出，由调用方决定是重排还是放弃。 */
    void delete(NewApiDeletionTaskEntity task) {
        if (NewApiDeletionTaskEntity.MODE_ADMIN.equals(task.getMode())
                && task.getNewapiUserId() != null) {
            newApi.hardDeleteUser(task.getNewapiUserId());
            return;
        }
        /*
         * 自助路径：New API 只认密码。用户名与密码都是入队那一刻的，账号之后没有登录过
         * （本地行已删），所以不会出现「密码又变了」的情况。
         */
        String password = queue.passwordOf(task);
        NewApiLoginSession login = newApi.login(task.getUsername(), password);
        newApi.deleteUser(login.accessToken(), password);
    }
}
