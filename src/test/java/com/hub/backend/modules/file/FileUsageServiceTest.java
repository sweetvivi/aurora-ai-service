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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileUsageServiceTest {

    private static final CurrentUser OWNER = new CurrentUser(7L, 1L, "demo");

    @Mock
    private UploadedFileRepository fileRepository;

    @Mock
    private AiCanvasAssetRepository canvasAssetRepository;

    @Mock
    private CommerceGenerationHistoryRepository commerceHistoryRepository;

    @Mock
    private AgentChatMessageRepository agentChatMessageRepository;

    private FileUsageService service;

    @BeforeEach
    void setUp() {
        service = new FileUsageService(
                fileRepository, canvasAssetRepository, commerceHistoryRepository, agentChatMessageRepository);
        when(canvasAssetRepository.findByUserIdAndSourceFileIdInAndStatus(anyLong(), any(), anyString()))
                .thenReturn(List.of());
        when(commerceHistoryRepository.findByResultUrlFragment(anyLong(), anyString())).thenReturn(List.of());
        when(agentChatMessageRepository.findConversationTitlesUsingAttachment(anyLong(), anyString()))
                .thenReturn(List.of());
    }

    @Test
    void reportsZeroWhenNothingElseUsesTheAttachment() {
        UploadedFileEntity file = ownedFile(11L, 7L);
        when(fileRepository.findAllById(List.of(11L))).thenReturn(List.of(file));

        List<FileUsageResponse> result = service.describe(OWNER, List.of(11L));

        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).fileId());
        assertEquals(0, result.get(0).total());
        assertTrue(result.get(0).groups().isEmpty());
    }

    @Test
    void countsEveryModuleThatReferencesTheAttachment() {
        UploadedFileEntity file = ownedFile(11L, 7L);
        AiCanvasAssetEntity asset = canvasAsset(11L, "商品主图");
        CommerceGenerationHistoryEntity first = commerceHistory("白底主图");
        CommerceGenerationHistoryEntity second = commerceHistory("白底主图");
        when(fileRepository.findAllById(List.of(11L))).thenReturn(List.of(file));
        when(canvasAssetRepository.findByUserIdAndSourceFileIdInAndStatus(anyLong(), any(), anyString()))
                .thenReturn(List.of(asset));
        when(commerceHistoryRepository.findByResultUrlFragment(anyLong(), anyString()))
                .thenReturn(List.of(first, second));
        when(agentChatMessageRepository.findConversationTitlesUsingAttachment(anyLong(), anyString()))
                .thenReturn(List.of("商品图需求讨论"));

        FileUsageResponse usage = service.describe(OWNER, List.of(11L)).get(0);

        assertEquals(4, usage.total());
        assertEquals(List.of("canvas", "commerce", "agent"),
                usage.groups().stream().map(FileUsageResponse.Group::module).toList());
        assertEquals(2, usage.groups().get(1).count());
        // 同一个工具名重复出现只留一条样本
        assertEquals(List.of("白底主图"), usage.groups().get(1).samples());
        assertEquals(List.of("商品图需求讨论"), usage.groups().get(2).samples());
    }

    @Test
    void skipsAttachmentsOwnedBySomeoneElse() {
        UploadedFileEntity someoneElses = ownedFile(11L, 99L);
        when(fileRepository.findAllById(List.of(11L))).thenReturn(List.of(someoneElses));

        assertTrue(service.describe(OWNER, List.of(11L)).isEmpty());
        // 不是自己的附件连引用都不该去查，否则等于泄露别人的使用情况
        verifyNoInteractions(canvasAssetRepository);
    }

    @Test
    void keepsRequestOrderAndStopsAtFiftyIds() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 60; id++) {
            ids.add(id);
        }
        List<UploadedFileEntity> files = new ArrayList<>();
        for (long id = 1; id <= 60; id++) {
            files.add(ownedFile(id, 7L));
        }
        when(fileRepository.findAllById(any())).thenReturn(files);

        List<FileUsageResponse> result = service.describe(OWNER, ids);

        assertEquals(FileUsageService.MAX_IDS, result.size());
        assertEquals(1L, result.get(0).fileId());
        assertEquals(50L, result.get(result.size() - 1).fileId());
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertTrue(service.describe(OWNER, List.of()).isEmpty());
        assertTrue(service.describe(OWNER, null).isEmpty());
        verifyNoInteractions(fileRepository);
    }

    private static UploadedFileEntity ownedFile(Long id, Long userId) {
        UploadedFileEntity file = mock(UploadedFileEntity.class);
        when(file.getId()).thenReturn(id);
        when(file.getUserId()).thenReturn(userId);
        return file;
    }

    private static AiCanvasAssetEntity canvasAsset(Long fileId, String name) {
        AiCanvasAssetEntity asset = mock(AiCanvasAssetEntity.class);
        when(asset.getSourceFileId()).thenReturn(fileId);
        when(asset.getName()).thenReturn(name);
        return asset;
    }

    private static CommerceGenerationHistoryEntity commerceHistory(String toolLabel) {
        CommerceGenerationHistoryEntity history = mock(CommerceGenerationHistoryEntity.class);
        when(history.getToolLabel()).thenReturn(toolLabel);
        return history;
    }
}
