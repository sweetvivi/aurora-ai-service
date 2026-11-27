package com.hub.backend.modules.chat;

import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.modules.agent.ChatAttachmentLoader;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the single "focus" image used when chat calls {@code edit_image} without a fresh upload.
 *
 * <ol>
 *   <li>Explicit {@code focusFileId} from the client ("以此图继续")</li>
 *   <li>Otherwise the newest {@code generated} / {@code edit_result} image on the conversation</li>
 * </ol>
 */
@Component
public class ChatImageFocusResolver {

    private static final int SCAN_PAGE = 24;

    private final UploadedFileRepository files;
    private final ChatAttachmentLoader attachmentLoader;

    public ChatImageFocusResolver(UploadedFileRepository files, ChatAttachmentLoader attachmentLoader) {
        this.files = files;
        this.attachmentLoader = attachmentLoader;
    }

    public List<LlmImageAttachment> resolve(CurrentUser currentUser,
                                            Long conversationId,
                                            Long focusFileId) {
        if (focusFileId != null && focusFileId > 0) {
            return attachmentLoader.loadForNewMessage(currentUser, List.of(focusFileId)).images();
        }
        if (conversationId == null) {
            return List.of();
        }
        Long latestId = latestGeneratedFileId(currentUser.userId(), conversationId);
        if (latestId == null) {
            return List.of();
        }
        return attachmentLoader.loadForNewMessage(currentUser, List.of(latestId)).images();
    }

    public boolean conversationHasGeneratedImage(Long userId, Long conversationId) {
        return latestGeneratedFileId(userId, conversationId) != null;
    }

    Long latestGeneratedFileId(Long userId, Long conversationId) {
        var page = files.findByUserIdAndConversationIdAndStatusOrderByIdDesc(
                userId, conversationId, UploadedFileEntity.STATUS_UPLOADED,
                PageRequest.of(0, SCAN_PAGE));
        for (UploadedFileEntity row : page.getContent()) {
            FileAssetType type = row.getAssetType();
            if (type != FileAssetType.GENERATED && type != FileAssetType.EDIT_RESULT) {
                continue;
            }
            String mime = row.getMimeType();
            if (mime == null || !mime.toLowerCase().startsWith("image/")) {
                continue;
            }
            return row.getId();
        }
        return null;
    }
}
