package com.hub.backend.modules.appconfig;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.entity.UserIdentityEntity;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalDataInitializerTest {

    @Mock
    private AppSiteRepository sites;

    @Mock
    private UserAccountRepository accounts;

    @Mock
    private UserIdentityRepository identities;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void repairsAnExistingDemoAccountThatHasNoPasswordIdentity() {
        AppSiteEntity site = new AppSiteEntity("default", "Default Site");
        ReflectionTestUtils.setField(site, "id", 1L);
        UserAccountEntity demo = new UserAccountEntity(1L, "demo", "Demo User");
        ReflectionTestUtils.setField(demo, "id", 10L);

        when(sites.findBySiteKey("default")).thenReturn(Optional.of(site));
        when(accounts.findFirstBySiteIdAndUsername(1L, "demo")).thenReturn(Optional.of(demo));
        when(passwordEncoder.encode("123456")).thenReturn("encoded-demo-password");
        when(identities.existsByProviderAndProviderSubject("password", "default:demo"))
                .thenReturn(false);

        LocalDataInitializer initializer = new LocalDataInitializer(
                sites, accounts, identities, passwordEncoder);
        initializer.run(null);

        assertEquals("encoded-demo-password", demo.getPasswordHash());
        verify(accounts).save(demo);

        ArgumentCaptor<UserIdentityEntity> identity = ArgumentCaptor.forClass(UserIdentityEntity.class);
        verify(identities).save(identity.capture());
        assertEquals(10L, identity.getValue().getUserId());
        assertEquals(UserIdentityEntity.PROVIDER_PASSWORD, identity.getValue().getProvider());
        assertEquals("default:demo", identity.getValue().getProviderSubject());
    }

    @Test
    void doesNotOverwriteAnExistingDemoPassword() {
        AppSiteEntity site = new AppSiteEntity("default", "Default Site");
        ReflectionTestUtils.setField(site, "id", 1L);
        UserAccountEntity demo = new UserAccountEntity(1L, "demo", "Demo User");
        demo.setPasswordHash("existing-password-hash");

        when(sites.findBySiteKey("default")).thenReturn(Optional.of(site));
        when(accounts.findFirstBySiteIdAndUsername(1L, "demo")).thenReturn(Optional.of(demo));
        when(identities.existsByProviderAndProviderSubject("password", "default:demo"))
                .thenReturn(true);

        LocalDataInitializer initializer = new LocalDataInitializer(
                sites, accounts, identities, passwordEncoder);
        initializer.run(null);

        assertEquals("existing-password-hash", demo.getPasswordHash());
        verify(accounts, org.mockito.Mockito.never()).save(any(UserAccountEntity.class));
        verify(identities, org.mockito.Mockito.never()).save(any(UserIdentityEntity.class));
    }
}
