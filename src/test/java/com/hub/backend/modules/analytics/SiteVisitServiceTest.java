package com.hub.backend.modules.analytics;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.analytics.dto.SiteVisitResponse;
import com.hub.backend.modules.analytics.entity.SiteVisitEntity;
import com.hub.backend.modules.analytics.repository.SiteVisitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SiteVisitServiceTest {

    @Mock
    private SiteVisitRepository repository;

    @Test
    void recordsThePathWithoutItsQueryString() {
        SiteVisitService service = new SiteVisitService(repository);

        SiteVisitResponse response = service.record(7L, "/painting?source=home");

        ArgumentCaptor<SiteVisitEntity> visit = ArgumentCaptor.forClass(SiteVisitEntity.class);
        verify(repository).save(visit.capture());
        assertThat(response.recorded()).isTrue();
        assertThat(visit.getValue().getSiteId()).isEqualTo(7L);
        assertThat(visit.getValue().getPath()).isEqualTo("/painting");
    }

    @Test
    void ignoresAdminPages() {
        SiteVisitService service = new SiteVisitService(repository);

        SiteVisitResponse response = service.record(7L, "/admin/users");

        assertThat(response.recorded()).isFalse();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsPathsThatAreNotSiteRelative() {
        SiteVisitService service = new SiteVisitService(repository);

        assertThatThrownBy(() -> service.record(7L, "https://example.com/"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("start with /");
    }
}
