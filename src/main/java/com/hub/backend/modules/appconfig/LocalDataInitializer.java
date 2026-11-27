package com.hub.backend.modules.appconfig;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.entity.UserIdentityEntity;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the local database with the one site needed to open the public application and repairs
 * the demo account if Flyway created it without password credentials.
 *
 * <p>Providers and models are intentionally not seeded here: New API is their source of truth.</p>
 */
@Component
public class LocalDataInitializer implements ApplicationRunner {

    private static final String DEMO_PASSWORD = "123456";

    private final AppSiteRepository sites;
    private final UserAccountRepository accounts;
    private final UserIdentityRepository identities;
    private final PasswordEncoder passwordEncoder;

    public LocalDataInitializer(
            AppSiteRepository sites,
            UserAccountRepository accounts,
            UserIdentityRepository identities,
            PasswordEncoder passwordEncoder
    ) {
        this.sites = sites;
        this.accounts = accounts;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppSiteEntity site = sites.findBySiteKey("default").orElse(null);
        if (site == null && sites.count() != 0) {
            return;
        }
        if (site == null) {
            site = sites.save(new AppSiteEntity("default", "Default Site"));
        }

        ensureDemoAccount(site);
    }

    private void ensureDemoAccount(AppSiteEntity site) {
        String subject = UserIdentityEntity.passwordSubject(site.getSiteKey(), "demo");
        UserAccountEntity demo = accounts.findFirstBySiteIdAndUsername(site.getId(), "demo")
                .orElse(null);

        if (demo == null) {
            if (identities.existsByProviderAndProviderSubject(
                    UserIdentityEntity.PROVIDER_PASSWORD, subject)) {
                return;
            }
            demo = accounts.save(new UserAccountEntity(site.getId(), "demo", "演示用户"));
        }

        if (demo.getPasswordHash() == null || demo.getPasswordHash().isBlank()) {
            demo.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
            accounts.save(demo);
        }

        if (!identities.existsByProviderAndProviderSubject(
                UserIdentityEntity.PROVIDER_PASSWORD, subject)) {
            identities.save(new UserIdentityEntity(
                    demo.getId(),
                    UserIdentityEntity.PROVIDER_PASSWORD,
                    subject));
        }
    }
}
