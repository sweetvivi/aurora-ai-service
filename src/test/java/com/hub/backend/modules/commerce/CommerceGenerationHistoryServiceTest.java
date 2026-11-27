package com.hub.backend.modules.commerce;

import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.commerce.entity.CommerceGenerationHistoryEntity;
import com.hub.backend.modules.commerce.repository.CommerceGenerationHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceGenerationHistoryServiceTest {

    private static final CurrentUser USER = new CurrentUser(7L, 2L, "tester");

    @Test
    void listIsScopedToSiteAndUser() {
        CommerceGenerationHistoryRepository repository = mock(CommerceGenerationHistoryRepository.class);
        when(repository.findBySiteIdAndUserIdOrderByCreatedAtDescIdDesc(eq(2L), eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of()));

        new CommerceGenerationHistoryService(repository).list(USER, 0, 20);

        verify(repository).findBySiteIdAndUserIdOrderByCreatedAtDescIdDesc(eq(2L), eq(7L), any());
    }

    @Test
    void recordTruncatesPromptBeforeSaving() {
        CommerceGenerationHistoryRepository repository = mock(CommerceGenerationHistoryRepository.class);
        String longPrompt = "x".repeat(5000);

        new CommerceGenerationHistoryService(repository)
                .record(USER, "tool", "工具", longPrompt, "/api/v1/files/1/content");

        verify(repository).save(any(CommerceGenerationHistoryEntity.class));
    }

    @Test
    void deleteRejectsRowsOutsideTheCurrentAccount() {
        CommerceGenerationHistoryRepository repository = mock(CommerceGenerationHistoryRepository.class);
        when(repository.findByIdAndSiteIdAndUserId(9L, 2L, 7L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> new CommerceGenerationHistoryService(repository).delete(USER, 9L));
    }
}
