package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.CoverRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.TitleRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowIdResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowSaveRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowSummaryResponse;
import com.hub.backend.modules.aicanvas.entity.AiCanvasAssetEntity;
import com.hub.backend.modules.aicanvas.entity.AiCanvasWorkflowEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasAssetRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasWorkflowRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.toUtc;

/**
 * Owner-scoped persistence for complete AI Canvas workflow documents.
 */
@Service
public class AiCanvasWorkflowService {

    private static final String ASSET_URL_PREFIX = "/api/v1/ai-canvas/assets/%s/content";

    private final AiCanvasWorkflowRepository repository;
    private final AiCanvasAssetRepository assetRepository;
    private final AiCanvasJsonCodec jsonCodec;

    public AiCanvasWorkflowService(AiCanvasWorkflowRepository repository,
                                   AiCanvasAssetRepository assetRepository,
                                   AiCanvasJsonCodec jsonCodec) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummaryResponse> list(CurrentUser currentUser) {
        return repository.findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
                        currentUser.userId(), AiCanvasWorkflowEntity.STATUS_ACTIVE)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(String id, CurrentUser currentUser) {
        return toResponse(requireOwned(id, currentUser));
    }

    @Transactional
    public WorkflowIdResponse save(CurrentUser currentUser, WorkflowSaveRequest request) {
        AiCanvasJsonCodec.requireArray(request.nodes(), "nodes");
        AiCanvasJsonCodec.requireArray(request.groups(), "groups");
        AiCanvasJsonCodec.requireObject(request.viewport(), "viewport");

        String id = request.id() == null || request.id().isBlank()
                ? AiCanvasJsonCodec.newId("wf")
                : request.id().strip();
        String title = request.title().strip();
        String description = request.description() == null ? null : request.description().strip();
        String nodes = jsonCodec.write(request.nodes());
        String groups = jsonCodec.write(request.groups());
        String viewport = jsonCodec.write(request.viewport());

        AiCanvasAssetEntity cover = resolveCover(currentUser, request.coverKey());
        repoFindOwned(id, currentUser).ifPresentOrElse(existing -> {
            existing.updateDocument(title, description, nodes, groups, viewport);
            if (cover != null) {
                existing.updateCover(cover.getKey(), assetUrl(cover.getKey()));
            }
        }, () -> repository.save(new AiCanvasWorkflowEntity(
                id,
                currentUser.userId(),
                title,
                description,
                nodes,
                groups,
                viewport,
                cover == null ? null : cover.getKey(),
                cover == null ? null : assetUrl(cover.getKey()),
                AiCanvasWorkflowEntity.STATUS_ACTIVE)));
        return new WorkflowIdResponse(id);
    }

    @Transactional
    public void rename(String id, CurrentUser currentUser, TitleRequest request) {
        requireOwned(id, currentUser).updateTitle(request.title().strip());
    }

    @Transactional
    public void updateCover(String id, CurrentUser currentUser, CoverRequest request) {
        AiCanvasWorkflowEntity workflow = requireOwned(id, currentUser);
        AiCanvasAssetEntity cover = requireOwnedAsset(request.coverKey().strip(), currentUser);
        workflow.updateCover(cover.getKey(), assetUrl(cover.getKey()));
    }

    @Transactional
    public void delete(String id, CurrentUser currentUser) {
        repository.delete(requireOwned(id, currentUser));
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummaryResponse> publicWorkflows() {
        return repository.findByStatusOrderByCreatedAtAsc(AiCanvasWorkflowEntity.STATUS_PUBLIC)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse publicWorkflow(String id) {
        AiCanvasWorkflowEntity workflow = repository
                .findByIdAndStatus(id, AiCanvasWorkflowEntity.STATUS_PUBLIC)
                .orElseThrow(() -> notFound(id));
        return toResponse(workflow);
    }

    private java.util.Optional<AiCanvasWorkflowEntity> repoFindOwned(String id, CurrentUser currentUser) {
        return repository.findByIdAndUserIdAndStatus(
                id, currentUser.userId(), AiCanvasWorkflowEntity.STATUS_ACTIVE);
    }

    private AiCanvasWorkflowEntity requireOwned(String id, CurrentUser currentUser) {
        return repoFindOwned(id, currentUser).orElseThrow(() -> notFound(id));
    }

    private AiCanvasAssetEntity resolveCover(CurrentUser currentUser, String coverKey) {
        if (coverKey == null || coverKey.isBlank()) {
            return null;
        }
        return requireOwnedAsset(coverKey.strip(), currentUser);
    }

    private AiCanvasAssetEntity requireOwnedAsset(String key, CurrentUser currentUser) {
        return assetRepository.findByKeyAndUserIdAndStatus(
                        key, currentUser.userId(), AiCanvasAssetEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "AI Canvas asset '%s' was not found".formatted(key)));
    }

    private WorkflowResponse toResponse(AiCanvasWorkflowEntity entity) {
        return new WorkflowResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                jsonCodec.read(entity.getNodesJson()),
                jsonCodec.read(entity.getGroupsJson()),
                jsonCodec.read(entity.getViewportJson()),
                toUtc(entity.getCreatedAt()),
                toUtc(entity.getUpdatedAt()),
                entity.getCoverKey(),
                normalizeCoverUrl(entity));
    }

    private WorkflowSummaryResponse toSummary(AiCanvasWorkflowEntity entity) {
        int nodeCount = jsonCodec.read(entity.getNodesJson()).size();
        return new WorkflowSummaryResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                toUtc(entity.getCreatedAt()),
                toUtc(entity.getUpdatedAt()),
                nodeCount,
                entity.getCoverKey(),
                normalizeCoverUrl(entity));
    }

    private static String normalizeCoverUrl(AiCanvasWorkflowEntity entity) {
        if (entity.getCoverKey() != null && !entity.getCoverKey().isBlank()) {
            return entity.getCoverUrl() == null || entity.getCoverUrl().isBlank()
                    ? assetUrl(entity.getCoverKey())
                    : entity.getCoverUrl();
        }
        return entity.getCoverUrl();
    }

    private static String assetUrl(String key) {
        return ASSET_URL_PREFIX.formatted(key);
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("AI Canvas workflow '%s' was not found".formatted(id));
    }
}
