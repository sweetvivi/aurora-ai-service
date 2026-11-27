package com.hub.backend.modules.file;

import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.infra.queue.TaskMessage;
import com.hub.backend.infra.queue.TaskQueue;
import com.hub.backend.infra.storage.FileStorage;
import com.hub.backend.infra.storage.StoredObject;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.repository.HiddenWorkRepository;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceTest {

    private static final CurrentUser OWNER = new CurrentUser(7L, 1L, "demo");
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    @Mock
    private UploadedFileRepository repository;
    @Mock
    private HiddenWorkRepository hiddenWorkRepository;
    @Mock
    private FileStorage storage;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private TaskQueue taskQueue;

    private StorageProperties properties;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setMaxFileSize(DataSize.ofKilobytes(1));
        fileService = new FileService(repository, hiddenWorkRepository, storage, properties,
                conversationService, events, taskQueue);
    }

    @Test
    void rejectsAnEmptyUpload() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> fileService.upload(OWNER, file, null));

        assertTrue(exception.getMessage().contains("文件内容为空"));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsAnUploadThatWouldExceedTheAccountQuota() {
        // 单文件上限挡不住反复上传：出图、修图、附件都写同一张表，没有总量闸门就能写到磁盘满。
        properties.setMaxUserBytes(DataSize.ofMegabytes(1));
        when(repository.sumSizeBytesByUserIdAndStatus(
                OWNER.userId(), UploadedFileEntity.STATUS_UPLOADED))
                .thenReturn(DataSize.ofMegabytes(1).toBytes() - 4);

        PayloadTooLargeException exception = assertThrows(PayloadTooLargeException.class,
                () -> fileService.upload(OWNER, mockUpload("shot.png", "image/png"), null));

        assertTrue(exception.getMessage().contains("存储空间不足"));
        verifyNoInteractions(storage);
    }

    @Test
    void acceptsAnUploadWhileTheAccountStillHasRoom() {
        properties.setMaxUserBytes(DataSize.ofMegabytes(1));
        when(repository.sumSizeBytesByUserIdAndStatus(
                OWNER.userId(), UploadedFileEntity.STATUS_UPLOADED)).thenReturn(0L);
        when(storage.store(any(), any())).thenReturn(new StoredObject("2026/11/02/a.png", 8L, "c"));
        when(repository.save(any(UploadedFileEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("shot.png", fileService.upload(OWNER, mockUpload("shot.png", "image/png"), null)
                .originalName());
    }

    @Test
    void skipsTheQuotaCheckWhenItIsDisabled() {
        // 0 表示不限制：本地调试与测试环境不该被默认配额挡住。
        properties.setMaxUserBytes(DataSize.ofBytes(0));
        when(storage.store(any(), any())).thenReturn(new StoredObject("2026/11/02/b.png", 8L, "c"));
        when(repository.save(any(UploadedFileEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("shot.png", fileService.upload(OWNER, mockUpload("shot.png", "image/png"), null)
                .originalName());
    }

    @Test
    void rejectsAnOversizedUploadBeforeTouchingTheStore() {
        byte[] payload = new byte[2048];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", payload);

        PayloadTooLargeException exception = assertThrows(PayloadTooLargeException.class,
                () -> fileService.upload(OWNER, file, null));

        assertTrue(exception.getMessage().contains("1024"));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsAnExtensionOutsideTheAllowList() {
        MockMultipartFile file = mockUpload("payload.bin", "application/octet-stream");

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> fileService.upload(OWNER, file, null));

        assertTrue(exception.getMessage().contains("bin"));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsAContentTypeOutsideTheAllowList() {
        // the extension is allowed, the declared content type is not: both must match
        MockMultipartFile file = mockUpload("payload.png", "application/x-msdownload");

        assertThrows(InvalidRequestException.class, () -> fileService.upload(OWNER, file, null));

        verifyNoInteractions(storage);
    }

    @Test
    void rejectsAContentTypeFromAnotherFileFamily() {
        MockMultipartFile file = mockUpload("payload.png", "text/plain");

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> fileService.upload(OWNER, file, null));

        assertTrue(exception.getMessage().contains("不匹配"));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsAnImageWhoseBytesDoNotMatchItsExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.png", "image/png", "not-a-png".getBytes(StandardCharsets.UTF_8));

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> fileService.upload(OWNER, file, null));

        assertTrue(exception.getMessage().contains("文件内容与扩展名"));
        verifyNoInteractions(storage);
    }

    @Test
    void storesTheBlobAndItsMetadata() {
        MockMultipartFile file = mockUpload("shot.png", "image/png");
        when(storage.store(any(), any())).thenReturn(new StoredObject("2026/10/14/abc.png", 5L, "checksum"));
        when(repository.save(any(UploadedFileEntity.class))).thenAnswer(invocation -> {
            UploadedFileEntity entity = invocation.getArgument(0);
            assignId(entity, 5L);
            return entity;
        });

        UploadedFileResponse response = fileService.upload(OWNER, file, null);

        assertEquals(5L, response.id().longValue());
        assertEquals("shot.png", response.originalName());
        assertEquals("image/png", response.mimeType());
        assertEquals("png", response.extension());
        assertEquals(FileAssetType.CHAT_ATTACHMENT, response.assetType());
        assertEquals(5L, response.sizeBytes());
        assertEquals("checksum", response.checksumSha256());
        assertEquals(UploadedFileEntity.STATUS_UPLOADED, response.status());
        assertEquals("/api/v1/files/5/content", response.url());
        assertNull(response.conversationId());

        ArgumentCaptor<String> extension = ArgumentCaptor.forClass(String.class);
        verify(storage).store(any(), extension.capture());
        assertEquals("png", extension.getValue());
    }

    @Test
    void acceptsAWellFormedWavAttachment() {
        byte[] wav = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 4);
        MockMultipartFile file = new MockMultipartFile("file", "practice.wav", "audio/wav", wav);
        when(storage.store(any(), any())).thenReturn(new StoredObject("audio/practice.wav", 16L, "checksum"));
        when(repository.save(any(UploadedFileEntity.class))).thenAnswer(invocation -> {
            UploadedFileEntity entity = invocation.getArgument(0);
            assignId(entity, 6L);
            return entity;
        });

        UploadedFileResponse response = fileService.upload(OWNER, file, null);

        assertEquals("audio/wav", response.mimeType());
        assertEquals("wav", response.extension());
    }

    @Test
    void requiresTheConversationToBelongToTheAccount() {
        MockMultipartFile file = mockUpload("shot.png", "image/png");
        when(conversationService.requireOwned(anyLong(), anyLong()))
                .thenThrow(new NotFoundException("Conversation 42 was not found"));

        assertThrows(NotFoundException.class, () -> fileService.upload(OWNER, file, 42L));

        verifyNoInteractions(storage);
        verify(repository, never()).save(any());
    }

    @Test
    void removesTheBlobWhenTheMetadataInsertFails() {
        MockMultipartFile file = mockUpload("shot.png", "image/png");
        when(storage.store(any(), any())).thenReturn(new StoredObject("2026/10/14/abc.png", 5L, "checksum"));
        when(repository.save(any(UploadedFileEntity.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> fileService.upload(OWNER, file, null));

        verify(storage).delete("2026/10/14/abc.png");
    }

    @Test
    void deletingAnAttachmentQueuesTheBlobCleanupInsteadOfRemovingItInline() {
        UploadedFileEntity file = new UploadedFileEntity(OWNER.userId(), null, "2026/11/10/x.png",
                "x.png", "image/png", "png", FileAssetType.CHAT_ATTACHMENT, 8L, "checksum");
        when(repository.findByIdAndUserIdAndStatus(5L, OWNER.userId(), UploadedFileEntity.STATUS_UPLOADED))
                .thenReturn(java.util.Optional.of(file));

        fileService.delete(5L, OWNER);

        // 删盘改成了异步：行与消息在同一个事务里，删字节由 StorageCleanupHandler 重试到成功。
        // 这里只钉住「不再在事务体里同步删」和「消息确实投出去了」。
        verify(taskQueue).publish(TaskMessage.of(StorageCleanupHandler.QUEUE, "2026/11/10/x.png"));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void hidesAnotherAccountsAttachmentBehindNotFound() {
        when(repository.findByIdAndUserIdAndStatus(anyLong(), anyLong(), anyString()))
                .thenReturn(java.util.Optional.empty());

        assertThrows(NotFoundException.class, () -> fileService.get(9L, OWNER));
    }

    @Test
    void stripsDirectoryComponentsFromTheFileName() {
        assertEquals("evil.png", FileService.sanitizeName("../../evil.png"));
        assertEquals("evil.png", FileService.sanitizeName("C:\\temp\\evil.png"));
        assertEquals("attachment", FileService.sanitizeName("   "));
        assertEquals("attachment", FileService.sanitizeName(null));
    }

    @Test
    void derivesTheExtensionFromTheName() {
        assertEquals("png", FileService.extensionOf("a.PNG"));
        assertEquals("", FileService.extensionOf("noextension"));
        assertEquals("", FileService.extensionOf("trailing."));
    }

    @Test
    void resolvesBlankOrOctetStreamMimeFromExtension() {
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                FileService.resolveContentType("xlsx", "application/octet-stream"));
        assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                FileService.resolveContentType("docx", null));
        assertEquals("image/png", FileService.resolveContentType("png", "image/png"));
        assertEquals("text/plain", FileService.resolveContentType("png", "text/plain"));
    }

    @Test
    void acceptsOfficeUploadWhenBrowserSendsOctetStream() {
        byte[] payload = "PK\u0003\u0004fake-ooxml".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile(
                "file", "工资表.xlsx", "application/octet-stream", payload);
        when(storage.store(any(), any())).thenReturn(
                new StoredObject("2026/10/30/sheet.xlsx", (long) payload.length, "checksum"));
        when(repository.save(any(UploadedFileEntity.class))).thenAnswer(invocation -> {
            UploadedFileEntity entity = invocation.getArgument(0);
            assignId(entity, 8L);
            return entity;
        });

        UploadedFileResponse response = fileService.upload(OWNER, file, null);

        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.mimeType());
        assertEquals("xlsx", response.extension());
    }

    private static MockMultipartFile mockUpload(String fileName, String contentType) {
        byte[] content = fileName.toLowerCase().endsWith(".png")
                ? PNG_SIGNATURE
                : "hello".getBytes(StandardCharsets.UTF_8);
        return new MockMultipartFile("file", fileName, contentType, content);
    }

    private static void assignId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
