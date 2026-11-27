package com.hub.backend.modules.admin.reference;

import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only lists the admin console needs to fill its selects.
 *
 * <p>Without these the console asked an operator to type a numeric {@code siteId} or {@code groupId}
 * and treat "leave it empty" as "global" — a form that cannot show what the numbers mean, and that
 * only fails once the write path rejects the id.</p>
 */
@Service
@Transactional(readOnly = true)
public class AdminReferenceService {

    private final AppSiteRepository sites;
    private final LlmModelGroupRepository groups;

    public AdminReferenceService(AppSiteRepository sites, LlmModelGroupRepository groups) {
        this.sites = sites;
        this.groups = groups;
    }

    public List<AdminSiteResponse> sites() {
        return sites.findAll(Sort.by("id")).stream()
                .map(AdminSiteResponse::of)
                .toList();
    }

    /**
     * Every group, not the site-visible subset the public catalogue uses.
     *
     * <p>An operator filing a global model needs to see all of them, and a group belonging to another
     * site is still a legitimate choice for a row that is itself site scoped. Visibility rules belong
     * to the read path that end users hit, not to the management form.</p>
     */
    public List<AdminModelGroupResponse> modelGroups() {
        return groups.findAll(Sort.by("sortOrder").ascending().and(Sort.by("id").ascending())).stream()
                .map(AdminModelGroupResponse::of)
                .toList();
    }
}
