package com.hub.backend.infra.llm;

import com.hub.backend.infra.llm.dto.UpstreamModel;

import java.util.List;

/**
 * Port for asking a supplier which models it offers.
 *
 * <p>Used by the admin console when someone has just typed in a key: instead of transcribing model
 * names by hand, the console asks the supplier and the operator picks from the answer.</p>
 */
public interface UpstreamModelCatalogClient {

    /**
     * @throws com.hub.backend.common.exception.LlmInvocationException when the supplier is
     *         unreachable, rejects the credentials, or answers with an unusable body
     */
    List<UpstreamModel> listModels(ProviderCredentials credentials);
}
