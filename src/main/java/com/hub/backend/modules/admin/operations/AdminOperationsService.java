package com.hub.backend.modules.admin.operations;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.aicanvas.entity.AiCanvasTaskEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasTaskRepository;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.RemoteAccountCleanup;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AdminOperationsService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /** Shown in the console when the local row is gone but the New API account is not. */
    static final String REMOTE_ACCOUNT_LEFT_BEHIND =
            "本地账号已删除，但 New API 上的同名账号未能一并删除，请到 New API 后台确认";

    private final UserAccountRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final AiCanvasTaskRepository taskRepository;
    private final LlmModelRepository modelRepository;
    private final AppSiteRepository siteRepository;
    private final AppProperties appProperties;
    private final NewApiAccountIdentityService accountIdentity;
    private final UserGatewayTokenService gatewayTokenService;

    public AdminOperationsService(UserAccountRepository userRepository,
                                  ConversationRepository conversationRepository,
                                  AiCanvasTaskRepository taskRepository,
                                  LlmModelRepository modelRepository,
                                  AppSiteRepository siteRepository,
                                  AppProperties appProperties,
                                  NewApiAccountIdentityService accountIdentity,
                                  UserGatewayTokenService gatewayTokenService) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.taskRepository = taskRepository;
        this.modelRepository = modelRepository;
        this.siteRepository = siteRepository;
        this.appProperties = appProperties;
        this.accountIdentity = accountIdentity;
        this.gatewayTokenService = gatewayTokenService;
    }

    public AdminListResponse<AdminUserResponse> users(String keyword,
                                                      String status,
                                                      int page,
                                                      int pageSize) {
        Long siteId = defaultSiteId();
        String normalizedStatus = userStatus(status, true);
        Page<UserAccountEntity> result = userRepository.searchAdminUsers(
                siteId,
                normalizeKeyword(keyword),
                normalizedStatus,
                pageRequest(page, pageSize));
        return new AdminListResponse<>(
                result.getContent().stream().map(AdminUserResponse::from).toList(),
                result.getTotalElements());
    }

    @Transactional
    public AdminUserResponse createUser(AdminCreateUserRequest request) {
        Long siteId = defaultSiteId();
        String username = requiredTrimmed(request.username());
        if (username.length() < 3) {
            throw new InvalidRequestException("username must contain at least 3 characters");
        }
        if (userRepository.existsBySiteIdAndUsername(siteId, username)) {
            throw new ConflictException("Username '%s' is already in use".formatted(username));
        }

        String nickname = trimmedToNull(request.nickname());
        UserAccountEntity account = new UserAccountEntity(
                siteId,
                username,
                nickname == null ? username : nickname);
        account.setRemark(trimmedToNull(request.remark()));
        try {
            return AdminUserResponse.from(userRepository.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Username '%s' is already in use".formatted(username));
        }
    }

    @Transactional
    public AdminOperationResponse updateUserStatus(Long id, AdminUserStatusRequest request) {
        String status = userStatus(request.status(), false);
        UserAccountEntity account = requireUser(id);
        account.setStatus(status);
        return new AdminOperationResponse(true);
    }

    /**
     * Deletes an account from the admin console, including its New API identity.
     *
     * <p>The order is the opposite of a self-service deletion, and deliberately so. A user who
     * deletes their own account hands over a password that proves the remote account is theirs;
     * an operator has no such proof, so the only credential that can remove the upstream row is
     * the shared administrator. And because the binding row is {@code ON DELETE CASCADE}, the
     * remote id has to be read <em>before</em> the local delete — afterwards there is no record
     * left of which New API user this was.</p>
     *
     * <p>The local delete happens first for the same reason it does in the self-service flow: it
     * can still be refused by a foreign key, and a refusal must leave the upstream account alone.
     * If the remote call then fails in a way worth retrying, it is queued rather than lost.</p>
     */
    @Transactional
    public AdminOperationResponse deleteUser(Long id) {
        UserAccountEntity account = requireUser(id);
        Long remoteId = accountIdentity.boundNewApiUserId(id).orElse(null);
        String username = account.getUsername();

        try {
            userRepository.delete(account);
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            // Nothing has been sent upstream yet, so the two sides still agree.
            throw new ConflictException(
                    "User %d has related data and cannot be deleted".formatted(id));
        }

        RemoteAccountCleanup cleanup =
                gatewayTokenService.deleteRemoteAccountOfDeletedUser(id, username, remoteId);
        if (cleanup == RemoteAccountCleanup.LEFT_IN_PLACE) {
            return new AdminOperationResponse(true, REMOTE_ACCOUNT_LEFT_BEHIND);
        }
        return new AdminOperationResponse(true);
    }

    public AdminListResponse<AdminConversationResponse> conversations(String keyword,
                                                                      int page,
                                                                      int pageSize) {
        Page<ConversationEntity> result = conversationRepository.searchAdminConversations(
                normalizeKeyword(keyword),
                ConversationEntity.STATUS_DELETED,
                pageRequest(page, pageSize));
        Map<Long, String> usernames = usernamesByIds(result.getContent().stream()
                .map(ConversationEntity::getUserId)
                .collect(Collectors.toSet()));
        Map<Long, String> modelKeys = modelKeysByIds(result.getContent().stream()
                .map(ConversationEntity::getModelId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));

        List<AdminConversationResponse> items = result.getContent().stream()
                .map(conversation -> AdminConversationResponse.from(
                        conversation,
                        usernames.get(conversation.getUserId()),
                        modelKeys.get(conversation.getModelId())))
                .toList();
        return new AdminListResponse<>(items, result.getTotalElements());
    }

    @Transactional
    public AdminOperationResponse deleteConversation(Long id) {
        ConversationEntity conversation = conversationRepository
                .findFirstByIdAndStatusNot(id, ConversationEntity.STATUS_DELETED)
                .orElseThrow(() -> new NotFoundException(
                        "Conversation %d was not found".formatted(id)));
        conversation.softDelete(LocalDateTime.now());
        return new AdminOperationResponse(true);
    }

    public AdminListResponse<AdminTaskResponse> tasks(String keyword,
                                                      String status,
                                                      int page,
                                                      int pageSize) {
        Page<AiCanvasTaskEntity> result = taskRepository.searchAdminTasks(
                AiCanvasTaskEntity.TYPE_IMAGE,
                normalizeKeyword(keyword),
                taskStatus(status),
                pageRequest(page, pageSize));
        Map<Long, String> usernames = usernamesByIds(result.getContent().stream()
                .map(AiCanvasTaskEntity::getUserId)
                .collect(Collectors.toSet()));
        List<AdminTaskResponse> items = result.getContent().stream()
                .map(task -> AdminTaskResponse.from(task, usernames.get(task.getUserId())))
                .toList();
        return new AdminListResponse<>(items, result.getTotalElements());
    }

    private Long defaultSiteId() {
        String siteKey = appProperties.getDefaultKey();
        return siteRepository.findBySiteKey(siteKey)
                .map(AppSiteEntity::getId)
                .orElseThrow(() -> new NotFoundException(
                        "Default site '%s' was not found".formatted(siteKey)));
    }

    private UserAccountEntity requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "User %d was not found".formatted(id)));
    }

    private Map<Long, String> usernamesByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        UserAccountEntity::getId,
                        user -> user.getUsername() == null ? "" : user.getUsername(),
                        (first, ignored) -> first));
    }

    private Map<Long, String> modelKeysByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return modelRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(
                        LlmModelEntity::getId,
                        LlmModelEntity::getModelKey,
                        (first, ignored) -> first));
    }

    private static PageRequest pageRequest(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = pageSize < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        return PageRequest.of(
                safePage - 1,
                safePageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private static String normalizeKeyword(String keyword) {
        return trimmedToNull(keyword);
    }

    private static String userStatus(String value, boolean nullable) {
        String status = trimmedToNull(value);
        if (status == null && nullable) {
            return null;
        }
        if ("active".equalsIgnoreCase(status)) {
            return UserAccountEntity.STATUS_ACTIVE;
        }
        if ("disabled".equalsIgnoreCase(status)) {
            return UserAccountEntity.STATUS_DISABLED;
        }
        throw new InvalidRequestException("status must be active or disabled");
    }

    private static String taskStatus(String value) {
        String status = trimmedToNull(value);
        if (status == null) {
            return null;
        }
        return switch (status.toLowerCase()) {
            case "pending" -> AiCanvasTaskEntity.STATUS_PENDING;
            case "generating" -> AiCanvasTaskEntity.STATUS_RUNNING;
            case "success" -> AiCanvasTaskEntity.STATUS_SUCCEEDED;
            case "failed" -> AiCanvasTaskEntity.STATUS_FAILED;
            default -> throw new InvalidRequestException(
                    "status must be pending, generating, success or failed");
        };
    }

    private static String requiredTrimmed(String value) {
        String trimmed = trimmedToNull(value);
        if (trimmed == null) {
            throw new InvalidRequestException("username is required");
        }
        return trimmed;
    }

    private static String trimmedToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
