package com.hub.backend.modules.support;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.agent.AgentChatService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatMessagePayload;
import com.hub.backend.modules.support.entity.SupportBotConfigEntity;
import org.springframework.stereotype.Service;

/**
 * 站内客服对话入口。
 *
 * <p>费用记在管理端配置的平台 API Key 上，不扣终端用户余额——咨询客服不该再向用户收费，
 * 余额为 0 的用户也应能正常进线。</p>
 */
@Service
public class SupportChatService {

    private final SupportBotService botService;
    private final SupportKnowledgeService knowledgeService;
    private final AgentChatService agentChatService;

    public SupportChatService(SupportBotService botService,
                              SupportKnowledgeService knowledgeService,
                              AgentChatService agentChatService) {
        this.botService = botService;
        this.knowledgeService = knowledgeService;
        this.agentChatService = agentChatService;
    }

    public AgentChatService.PreparedAgentChat prepare(ChatCompletionRequest request, CurrentUser user) {
        SupportBotConfigEntity config = botService.requireConfig(user.siteId());
        if (!config.isEnabled()) {
            throw new InvalidRequestException("客服助手当前已关闭");
        }
        if (!botService.isOnlineNow(config)) {
            throw new InvalidRequestException("客服助手当前离线，请留言转人工");
        }
        SupportBotService.PlatformCredential platform = botService.requirePlatformCredential(config);
        String query = lastUserContent(request);
        String appendix = knowledgeService.buildAppendix(config.getSiteId(), query);
        // billToUser=false：走管理端配置的基地址 + 平台钥匙，不查用户额度
        return agentChatService.prepare(
                SupportConstants.AGENT_SN, request, user, appendix,
                false, platform.baseUrl(), platform.apiKey());
    }

    private static String lastUserContent(ChatCompletionRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        for (int i = request.messages().size() - 1; i >= 0; i -= 1) {
            ChatMessagePayload message = request.messages().get(i);
            if (message != null && "user".equalsIgnoreCase(message.role())) {
                return message.content() == null ? "" : message.content();
            }
        }
        return "";
    }
}
