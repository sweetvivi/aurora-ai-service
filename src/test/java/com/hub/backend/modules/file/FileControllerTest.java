package com.hub.backend.modules.file;

import com.hub.backend.infra.queue.entity.TaskQueueEntryEntity;
import com.hub.backend.infra.queue.repository.TaskQueueEntryRepository;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ChatMessageRepository;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileControllerTest extends AuthenticatedControllerTestSupport {

    private static final String FILES_URL = "/api/v1/files";
    private static final byte[] PNG_PAYLOAD =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    /** Created at class load so it exists before the Spring context is built. */
    private static final Path UPLOAD_ROOT = createTempRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", UPLOAD_ROOT::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private TaskQueueEntryRepository taskQueueEntries;

    @Autowired
    private StorageCleanupHandler storageCleanupHandler;

    private Long demoUserId;
    private Long otherUserId;
    private Long demoConversationId;
    private Long otherConversationId;
    private String demoToken;
    private String otherToken;

    @BeforeEach
    void seedAccounts() throws IOException {
        uploadedFileRepository.deleteAll();
        chatMessageRepository.deleteAll();
        conversationRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();
        clearUploadRoot();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        demoUserId = userAccountRepository.save(new UserAccountEntity(site.getId(), "demo", "Demo User")).getId();
        otherUserId = userAccountRepository.save(new UserAccountEntity(site.getId(), "other", "Other User")).getId();
        demoToken = accessToken(demoUserId);
        otherToken = accessToken(otherUserId);

        demoConversationId = conversationRepository.save(new ConversationEntity(demoUserId, null, "演示会话")).getId();
        otherConversationId = conversationRepository.save(new ConversationEntity(otherUserId, null, "别人的会话")).getId();
    }

    @Test
    void uploadsAnImageAndReturnsItsMetadata() throws Exception {
        mockMvc.perform(multipart(FILES_URL).file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.originalName").value("screenshot.png"))
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andExpect(jsonPath("$.extension").value("png"))
                .andExpect(jsonPath("$.assetType").value("chat_attachment"))
                .andExpect(jsonPath("$.sizeBytes").value(PNG_PAYLOAD.length))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/api/v1/files/")))
                .andExpect(jsonPath("$.checksumSha256").value(org.hamcrest.Matchers.hasLength(64)));

        assertEquals(1, uploadedFileRepository.count());
        assertEquals(1, countStoredBlobs());
    }

    @Test
    void downloadsTheStoredBytesAsAnAttachment() throws Exception {
        String body = mockMvc.perform(multipart(FILES_URL).file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("url").asText();

        byte[] downloaded = mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("image/png")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        assertArrayEquals(PNG_PAYLOAD, downloaded);
    }

    @Test
    void scopesAttachmentsToTheUploadingAccount() throws Exception {
        String body = mockMvc.perform(multipart(FILES_URL).file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();

        mockMvc.perform(get(FILES_URL + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(FILES_URL + "/" + id + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(FILES_URL + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // the owner still sees it
        mockMvc.perform(get(FILES_URL + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAContentTypeOutsideTheAllowList() throws Exception {
        MockMultipartFile executable = new MockMultipartFile(
                "file", "payload.bin", "application/octet-stream", PNG_PAYLOAD);

        mockMvc.perform(multipart(FILES_URL).file(executable)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertEquals(0, uploadedFileRepository.count());
        assertEquals(0, countStoredBlobs());
    }

    @Test
    void rejectsAnExtensionAndMimeTypeFromDifferentFileFamilies() throws Exception {
        MockMultipartFile disguised = new MockMultipartFile(
                "file", "image.png", "text/plain", PNG_PAYLOAD);

        mockMvc.perform(multipart(FILES_URL).file(disguised)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不匹配")));

        assertEquals(0, uploadedFileRepository.count());
        assertEquals(0, countStoredBlobs());
    }

    @Test
    void rejectsAnEmptyUpload() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(multipart(FILES_URL).file(empty)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("文件内容为空")));
    }

    @Test
    void linksAnAttachmentToAnOwnedConversationAndRejectsAForeignOne() throws Exception {
        mockMvc.perform(multipart(FILES_URL)
                        .file(png())
                        .param("conversationId", String.valueOf(demoConversationId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(demoConversationId.intValue()));

        mockMvc.perform(multipart(FILES_URL)
                        .file(png())
                        .param("conversationId", String.valueOf(otherConversationId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsOnlyTheAttachmentsOfTheAccountAndCanFilterByConversation() throws Exception {
        mockMvc.perform(multipart(FILES_URL).file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk());
        mockMvc.perform(multipart(FILES_URL)
                        .file(png())
                        .param("conversationId", String.valueOf(demoConversationId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk());
        mockMvc.perform(multipart(FILES_URL).file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(FILES_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)));

        mockMvc.perform(get(FILES_URL)
                        .param("conversationId", String.valueOf(demoConversationId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get(FILES_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void deletesAnAttachmentAndQueuesTheBlobCleanup() throws Exception {
        String body = mockMvc.perform(multipart(FILES_URL).file(png())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();
        String storageKey = uploadedFileRepository.findById(Long.valueOf(id)).orElseThrow().getStorageKey();

        assertEquals(1, countStoredBlobs());

        mockMvc.perform(delete(FILES_URL + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FILES_URL + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(FILES_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // soft deleted in the database
        assertEquals(1, uploadedFileRepository.count());

        // 删盘不再是提交后的同步动作，而是一条队列消息（与删行同事务）。这里先确认消息真的排进去了，
        // 再手动跑一次处理器 —— 调度器可能已经删过，处理器必须幂等，所以再跑一次同样成立。
        List<TaskQueueEntryEntity> queued = taskQueueEntries.findAll().stream()
                .filter(row -> StorageCleanupHandler.QUEUE.equals(row.getQueueName()))
                .filter(row -> storageKey.equals(row.getPayload()))
                .toList();
        assertEquals(1, queued.size());

        storageCleanupHandler.handle(storageKey);
        assertEquals(0, countStoredBlobs());
    }

    @Test
    void filtersAttachmentsByTheFeatureThatProducedThem() throws Exception {
        // Several features write asset_type=generated; without a source filter the drawing gallery
        // listed the image-generation page's and the smart-editing workbench's pictures as its own.
        saveGenerated(demoUserId, "painting-1.png", FileSource.PAINTING);
        saveGenerated(demoUserId, "generated-1.png", FileSource.IMAGE_GENERATION);
        saveGenerated(otherUserId, "other-painting.png", FileSource.PAINTING);

        mockMvc.perform(get(FILES_URL).param("source", "painting")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].originalName").value("painting-1.png"));

        // the same account still sees everything when no source is asked for
        mockMvc.perform(get(FILES_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void refusesAnUnknownSourceInsteadOfReturningEverything() throws Exception {
        // Silently ignoring a typo would look like the filter works while showing the whole account.
        mockMvc.perform(get(FILES_URL).param("source", "not-a-feature")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishesFilePathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/files'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/files'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/files/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/files/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/files/{id}/content'].get").exists())
                .andExpect(jsonPath("$.components.schemas.UploadedFile").exists());
    }

    private void saveGenerated(Long userId, String name, FileSource source) {
        uploadedFileRepository.save(new UploadedFileEntity(
                userId,
                null,
                "stored/" + name,
                name,
                "image/png",
                "png",
                FileAssetType.GENERATED,
                source,
                PNG_PAYLOAD.length,
                "0".repeat(64)));
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "screenshot.png", "image/png", PNG_PAYLOAD);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("backend-file-controller-test");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long countStoredBlobs() throws IOException {
        if (!Files.isDirectory(UPLOAD_ROOT)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(UPLOAD_ROOT)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static void clearUploadRoot() throws IOException {
        if (!Files.isDirectory(UPLOAD_ROOT)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(UPLOAD_ROOT)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
        assertTrue(Files.isDirectory(UPLOAD_ROOT));
    }
}
