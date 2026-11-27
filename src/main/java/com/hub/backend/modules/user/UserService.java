package com.hub.backend.modules.user;

import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account lookups needed outside the authentication module.
 *
 * <p>Until the login module lands, chat requests are attributed to a configurable demo account
 * that is created on first use. This keeps the conversation tables populated with a valid owner
 * without inventing a fake authentication bypass.</p>
 */
@Service
@Transactional
public class UserService {

    private final UserAccountRepository repository;

    public UserService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserAccountEntity resolveDefaultUser(Long siteId, String username) {
        return repository.findFirstBySiteIdAndUsername(siteId, username)
                .orElseGet(() -> repository.save(new UserAccountEntity(siteId, username, username)));
    }

    @Transactional(readOnly = true)
    public UserAccountEntity requireById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new com.hub.backend.common.exception.NotFoundException(
                        "User %d was not found".formatted(id)));
    }
}
