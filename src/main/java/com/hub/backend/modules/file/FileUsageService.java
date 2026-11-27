package com.hub.backend.modules.file;

import com.hub.backend.modules.aicanvas.entity.AiCanvasAssetEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasAssetRepository;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.commerce.entity.CommerceGenerationHistoryEntity;
import com.hub.backend.modules.commerce.repository.CommerceGenerationHistoryRepository;
import com.hub.backend.modules.file.dto.FileUsageResponse;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 附件引用查询。
 *
 * <p>删除附件是**不可逆**的：{@link FileService#delete} 会连同存储对象一起清掉，之后所有
 * 指向它的页面都会失效（电商历史破图、对话附件 404、画布素材卡被清）。所以真正删除之前，
 * 先由这里回答一个问题：这张图还在哪些页面用着。</p>
 *
 * <p>三处引用形态各不相同，判断方式也不同：</p>
 * <ul>
 *   <li>画布素材：{@code ai_canvas_assets.source_file_id}，有真实的外键值，可以直接匹配；</li>
 *   <li>电商历史：只有 {@code result_url} 字符串，按 {@code /files/{id}/content} 片段匹配；</li>
 *   <li>AI 对话（含 AI 教育历史）：附件存在 {@code attachments_json} 里，同样按 URL 片段匹配。</li>
 * </ul>
 *
 * <p>查询限额是刻意的：一次最多 {@value #MAX_IDS} 个附件，超过的部分直接忽略。批量删除的
 * 选择框不会一次勾超过一屏的量，而没有上限的 LIKE 扫描很容易被拿来打库。</p>
 */
@Service
public class FileUsageService {

    /** 单次查询的附件上限 */
    static final int MAX_IDS = 50;

    /** 每个分组最多带几条上下文样本 */
    private static final int MAX_SAMPLES = 3;

    /** 样本字符串的长度上限，够认出是哪张图即可 */
    private static final int SAMPLE_LENGTH = 40;

    private final UploadedFileRepository fileRepository;
    private final AiCanvasAssetRepository canvasAssetRepository;
    private final CommerceGenerationHistoryRepository commerceHistoryRepository;
    private final AgentChatMessageRepository agentChatMessageRepository;

    public FileUsageService(UploadedFileRepository fileRepository,
                            AiCanvasAssetRepository canvasAssetRepository,
                            CommerceGenerationHistoryRepository commerceHistoryRepository,
                            AgentChatMessageRepository agentChatMessageRepository) {
        this.fileRepository = fileRepository;
        this.canvasAssetRepository = canvasAssetRepository;
        this.commerceHistoryRepository = commerceHistoryRepository;
        this.agentChatMessageRepository = agentChatMessageRepository;
    }

    /**
     * 批量描述引用情况。
     *
     * <p>只返回调用者自己的附件：别人的附件既不报引用、也不暴露存在性，直接跳过。</p>
     *
     * @return 与入参同序；没有引用的附件也会返回一条 {@code total = 0} 的记录，
     *         便于前端按 id 对齐，不用再猜哪个没返回
     */
    @Transactional(readOnly = true)
    public List<FileUsageResponse> describe(CurrentUser currentUser, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> requested = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_IDS)
                .toList();
        if (requested.isEmpty()) {
            return List.of();
        }

        Map<Long, UploadedFileEntity> owned = new HashMap<>();
        for (UploadedFileEntity file : fileRepository.findAllById(requested)) {
            if (Objects.equals(file.getUserId(), currentUser.userId())) {
                owned.put(file.getId(), file);
            }
        }
        if (owned.isEmpty()) {
            return List.of();
        }

        // 画布是一次批量查询，其余两处按片段逐张查：LIKE 没法用 IN 合并且总量很小
        Map<Long, List<AiCanvasAssetEntity>> canvasByFile = canvasAssetRepository
                .findByUserIdAndSourceFileIdInAndStatus(
                        currentUser.userId(), owned.keySet(), AiCanvasAssetEntity.STATUS_ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(AiCanvasAssetEntity::getSourceFileId));

        List<FileUsageResponse> result = new ArrayList<>(owned.size());
        for (Long fileId : requested) {
            if (!owned.containsKey(fileId)) {
                continue;
            }
            String fragment = "/files/%d/content".formatted(fileId);
            List<FileUsageResponse.Group> groups = new ArrayList<>(3);

            List<AiCanvasAssetEntity> assets = canvasByFile.getOrDefault(fileId, List.of());
            if (!assets.isEmpty()) {
                groups.add(new FileUsageResponse.Group(
                        "canvas", "AI 画布素材", assets.size(),
                        samples(assets.stream().map(AiCanvasAssetEntity::getName).toList())));
            }

            List<CommerceGenerationHistoryEntity> commerce =
                    commerceHistoryRepository.findByResultUrlFragment(currentUser.userId(), fragment);
            if (!commerce.isEmpty()) {
                groups.add(new FileUsageResponse.Group(
                        "commerce", "电商历史", commerce.size(),
                        samples(commerce.stream().map(CommerceGenerationHistoryEntity::getToolLabel).toList())));
            }

            List<String> titles = agentChatMessageRepository
                    .findConversationTitlesUsingAttachment(currentUser.userId(), fragment);
            if (!titles.isEmpty()) {
                groups.add(new FileUsageResponse.Group(
                        "agent", "AI 对话", titles.size(), samples(titles)));
            }

            int total = groups.stream().mapToInt(FileUsageResponse.Group::count).sum();
            result.add(new FileUsageResponse(fileId, total, List.copyOf(groups)));
        }
        return result;
    }

    /** 去掉空值、去重、截断，只留前几条能帮用户认出是哪张图的线索 */
    private static List<String> samples(List<String> raw) {
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(text -> !text.isEmpty())
                .distinct()
                .limit(MAX_SAMPLES)
                .map(FileUsageService::abbreviate)
                .toList();
    }

    private static String abbreviate(String text) {
        return text.length() <= SAMPLE_LENGTH
                ? text
                : text.substring(0, SAMPLE_LENGTH - 1) + "…";
    }
}
