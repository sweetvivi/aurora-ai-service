package com.hub.backend.modules.admin.operations;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.modules.aicanvas.repository.AiCanvasTaskRepository;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.RemoteAccountCleanup;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOperationsServiceTest {

    private static final long USER_ID = 7L;
    private static final long NEW_API_USER_ID = 4242L;

    private UserAccountRepository userRepository;
    private AppSiteRepository siteRepository;
    private NewApiAccountIdentityService accountIdentity;
    private UserGatewayTokenService gatewayTokenService;
    private AdminOperationsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserAccountRepository.class);
        siteRepository = mock(AppSiteRepository.class);
        accountIdentity = mock(NewApiAccountIdentityService.class);
        gatewayTokenService = mock(UserGatewayTokenService.class);

        UserAccountEntity account = new UserAccountEntity(1L, "referenced", "Referenced");
        when(siteRepository.findBySiteKey("default"))
                .thenReturn(Optional.of(new AppSiteEntity("default", "Default Site")));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));

        service = new AdminOperationsService(
                userRepository,
                mock(ConversationRepository.class),
                mock(AiCanvasTaskRepository.class),
                mock(LlmModelRepository.class),
                siteRepository,
                new AppProperties(),
                accountIdentity,
                gatewayTokenService);
    }

    @Test
    void reportsReferencedUsersAsAConflict() {
        doThrow(new DataIntegrityViolationException("foreign key"))
                .when(userRepository).flush();

        assertThrows(ConflictException.class, () -> service.deleteUser(USER_ID));
    }

    /**
     * 本地删除被外键拒了，New API 那边必须一行不动 —— 否则管理员看到的是「删除失败」，
     * 而用户的 AI 能力已经被悄悄拿掉了。
     */
    @Test
    void leavesTheRemoteAccountAloneWhenTheLocalDeleteIsRefused() {
        doThrow(new DataIntegrityViolationException("foreign key"))
                .when(userRepository).flush();

        assertThrows(ConflictException.class, () -> service.deleteUser(USER_ID));
        verify(gatewayTokenService, never())
                .deleteRemoteAccountOfDeletedUser(anyLong(), anyString(), any());
    }

    @Test
    void deletesTheNewApiAccountAfterTheLocalRowIsGone() {
        when(accountIdentity.boundNewApiUserId(USER_ID))
                .thenReturn(Optional.of(NEW_API_USER_ID));
        when(gatewayTokenService.deleteRemoteAccountOfDeletedUser(
                USER_ID, "referenced", NEW_API_USER_ID))
                .thenReturn(RemoteAccountCleanup.DELETED);

        AdminOperationResponse response = service.deleteUser(USER_ID);

        assertEquals(true, response.ok());
        assertNull(response.warning(), "远端删掉了就没有什么要对管理员说的");

        // 绑定行随本地删除一起 CASCADE 掉，所以远端 id 必须先读、后删。
        InOrder order = inOrder(userRepository, gatewayTokenService);
        order.verify(userRepository).flush();
        order.verify(gatewayTokenService)
                .deleteRemoteAccountOfDeletedUser(USER_ID, "referenced", NEW_API_USER_ID);
    }

    @Test
    void tellsTheOperatorWhenTheNewApiAccountSurvives() {
        when(accountIdentity.boundNewApiUserId(USER_ID))
                .thenReturn(Optional.of(NEW_API_USER_ID));
        when(gatewayTokenService.deleteRemoteAccountOfDeletedUser(
                USER_ID, "referenced", NEW_API_USER_ID))
                .thenReturn(RemoteAccountCleanup.LEFT_IN_PLACE);

        AdminOperationResponse response = service.deleteUser(USER_ID);

        // 本地删成功就算成功：把整次调用判失败只会让管理员重复点，而本地行已经没了。
        assertEquals(true, response.ok());
        assertEquals(AdminOperationsService.REMOTE_ACCOUNT_LEFT_BEHIND, response.warning());
    }

    @Test
    void doesNotAccuseNewApiWhenTheAccountNeverHadARemoteIdentity() {
        when(accountIdentity.boundNewApiUserId(USER_ID)).thenReturn(Optional.empty());
        when(gatewayTokenService.deleteRemoteAccountOfDeletedUser(USER_ID, "referenced", null))
                .thenReturn(RemoteAccountCleanup.NOT_APPLICABLE);

        assertNull(service.deleteUser(USER_ID).warning());
    }
}
