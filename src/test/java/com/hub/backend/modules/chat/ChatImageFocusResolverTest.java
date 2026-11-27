package com.hub.backend.modules.chat;

import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.modules.agent.ChatAttachmentLoader;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatImageFocusResolverTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");

    @Mock
    private UploadedFileRepository files;
    @Mock
    private ChatAttachmentLoader attachmentLoader;

    private ChatImageFocusResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChatImageFocusResolver(files, attachmentLoader);
    }

    @Test
    void prefersExplicitFocusFileId() {
        when(attachmentLoader.loadForNewMessage(eq(USER), eq(List.of(99L))))
                .thenReturn(new ChatAttachmentLoader.Loaded(
                        List.of(new LlmImageAttachment("image/png", new byte[] {1})),
                        List.of(),
                        "",
                        ""));

        List<LlmImageAttachment> resolved = resolver.resolve(USER, 42L, 99L);

        assertEquals(1, resolved.size());
        verify(attachmentLoader).loadForNewMessage(USER, List.of(99L));
    }

    @Test
    void fallsBackToLatestGeneratedInConversation() {
        UploadedFileEntity generated = entity(55L, FileAssetType.GENERATED, "image/png");
        when(files.findByUserIdAndConversationIdAndStatusOrderByIdDesc(
                eq(7L), eq(42L), eq(UploadedFileEntity.STATUS_UPLOADED), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(generated)));
        when(attachmentLoader.loadForNewMessage(eq(USER), eq(List.of(55L))))
                .thenReturn(new ChatAttachmentLoader.Loaded(
                        List.of(new LlmImageAttachment("image/png", new byte[] {9})),
                        List.of(),
                        "",
                        ""));

        List<LlmImageAttachment> resolved = resolver.resolve(USER, 42L, null);

        assertEquals(1, resolved.size());
        assertTrue(resolver.conversationHasGeneratedImage(7L, 42L));
    }

    @Test
    void skipsNonImageAttachmentsWhenScanningConversation() {
        UploadedFileEntity doc = entity(1L, FileAssetType.GENERATED, "application/pdf");
        UploadedFileEntity image = entity(2L, FileAssetType.EDIT_RESULT, "image/jpeg");
        when(files.findByUserIdAndConversationIdAndStatusOrderByIdDesc(
                eq(7L), eq(42L), eq(UploadedFileEntity.STATUS_UPLOADED), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(doc, image)));

        assertEquals(2L, resolver.latestGeneratedFileId(7L, 42L));
    }

    @Test
    void emptyWhenNoConversationAndNoFocus() {
        assertTrue(resolver.resolve(USER, null, null).isEmpty());
        when(files.findByUserIdAndConversationIdAndStatusOrderByIdDesc(
                eq(7L), eq(42L), eq(UploadedFileEntity.STATUS_UPLOADED), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertFalse(resolver.conversationHasGeneratedImage(7L, 42L));
    }

    private static UploadedFileEntity entity(Long id, FileAssetType type, String mime) {
        UploadedFileEntity row = new UploadedFileEntity(
                7L, 42L, "key-" + id, "file", mime, "bin", type, null, 10L, "checksum");
        try {
            var field = UploadedFileEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(row, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return row;
    }
}
