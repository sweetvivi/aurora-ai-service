package com.hub.backend.modules.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.agent.AgentService;
import com.hub.backend.modules.agent.dto.AgentAdminRequest;
import com.hub.backend.modules.agent.dto.AgentResponse;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.repository.AgentRepository;
import com.hub.backend.modules.support.dto.SupportBotConfigResponse;
import com.hub.backend.modules.support.dto.SupportBotConfigUpdateRequest;
import com.hub.backend.modules.support.dto.SupportFaqItemResponse;
import com.hub.backend.modules.support.dto.SupportWidgetConfigResponse;
import com.hub.backend.modules.support.entity.SupportBotConfigEntity;
import com.hub.backend.modules.support.entity.SupportFaqEntity;
import com.hub.backend.modules.support.repository.SupportBotConfigRepository;
import com.hub.backend.modules.support.repository.SupportFaqRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class SupportBotService {

    private final SupportBotConfigRepository configs;
    private final SupportFaqRepository faqs;
    private final AgentRepository agents;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    public SupportBotService(SupportBotConfigRepository configs,
                             SupportFaqRepository faqs,
                             AgentRepository agents,
                             AgentService agentService,
                             ObjectMapper objectMapper) {
        this.configs = configs;
        this.faqs = faqs;
        this.agents = agents;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    public SupportWidgetConfigResponse widgetConfig(Long siteId) {
        SupportBotConfigEntity config = requireConfig(siteId);
        AgentEntity agent = requireAgent();
        List<SupportFaqItemResponse> faqItems = faqs
                .findBySiteIdAndEnabledTrueOrderBySortOrderAscIdAsc(config.getSiteId())
                .stream()
                .map(this::toFaqItem)
                .toList();
        boolean online = config.isEnabled() && isOnlineNow(config);
        return new SupportWidgetConfigResponse(
                config.isEnabled(),
                online,
                config.getOnlineMode(),
                config.getHomeTitle(),
                config.getWelcomeTitle(),
                config.getWelcomeDescription(),
                config.getBrandName(),
                config.getBrandColor(),
                agent.getSn(),
                agent.getChatPrologue() == null ? "" : agent.getChatPrologue(),
                agent.getName(),
                agent.getIconUrl() == null ? "" : agent.getIconUrl(),
                faqItems);
    }

    public SupportBotConfigResponse adminConfig(Long siteId) {
        SupportBotConfigEntity config = requireConfig(siteId);
        AgentEntity agent = requireAgent();
        AgentResponse detail = agentService.detailAdmin(agent.getId());
        return toConfigResponse(config, agent, detail);
    }

    @Transactional
    public SupportBotConfigResponse updateConfig(Long siteId, SupportBotConfigUpdateRequest request) {
        SupportBotConfigEntity config = requireConfig(siteId);
        AgentEntity agent = requireAgent();

        if (request.enabled() != null) {
            config.setEnabled(request.enabled());
        }
        if (request.onlineMode() != null && !request.onlineMode().isBlank()) {
            String mode = request.onlineMode().strip().toUpperCase(Locale.ROOT);
            if (!SupportConstants.ONLINE_ALWAYS.equals(mode)
                    && !SupportConstants.ONLINE_SCHEDULED.equals(mode)) {
                throw new InvalidRequestException("在线模式只能是 ALWAYS（始终在线）或 SCHEDULED（按计划）");
            }
            config.setOnlineMode(mode);
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            try {
                ZoneId.of(request.timezone().strip());
            } catch (Exception exception) {
                throw new InvalidRequestException("时区无效，请使用如 Asia/Shanghai 的标准时区");
            }
            config.setTimezone(request.timezone().strip());
        }
        if (request.scheduleJson() != null) {
            config.setScheduleJson(request.scheduleJson().isBlank() ? null : request.scheduleJson());
        }
        if (request.welcomeTitle() != null) {
            config.setWelcomeTitle(blankToDefault(request.welcomeTitle(), "欢迎"));
        }
        if (request.welcomeDescription() != null) {
            config.setWelcomeDescription(blankToDefault(request.welcomeDescription(),
                    "有问题随时问我，也可以转人工处理。"));
        }
        if (request.homeTitle() != null) {
            config.setHomeTitle(blankToDefault(request.homeTitle(), "AI 助手"));
        }
        if (request.brandName() != null) {
            config.setBrandName(blankToDefault(request.brandName(), "AI 客服"));
        }
        if (request.brandColor() != null && !request.brandColor().isBlank()) {
            config.setBrandColor(request.brandColor().strip());
        }
        // null = 不改；"" = 清除；非空 = 覆盖。
        if (request.platformBaseUrl() != null) {
            config.setPlatformBaseUrl(normalizeBaseUrl(request.platformBaseUrl()));
        }
        // 密钥永不经 GET 回显。
        if (request.platformApiKey() != null) {
            String key = request.platformApiKey().strip();
            config.setPlatformApiKey(key.isEmpty() ? null : key);
        }
        configs.save(config);

        boolean touchAgent = request.agentName() != null
                || request.agentIconUrl() != null
                || request.systemPrompt() != null
                || request.chatPrologue() != null
                || request.modelIds() != null
                || request.defaultModelId() != null;
        if (touchAgent) {
            AgentAdminRequest adminRequest = new AgentAdminRequest(
                    null, null, null, null, null, null, null, null,
                    request.agentName(),
                    null,
                    request.agentIconUrl(),
                    request.systemPrompt(),
                    request.chatPrologue(),
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null,
                    request.modelIds(),
                    request.defaultModelId(),
                    null, null, null, null);
            agentService.updateAdmin(agent.getId(), adminRequest);
        }

        AgentEntity refreshed = requireAgent();
        AgentResponse detail = agentService.detailAdmin(refreshed.getId());
        return toConfigResponse(config, refreshed, detail);
    }

    public AgentEntity requireAgent() {
        return agents.findFirstBySnAndStatus(SupportConstants.AGENT_SN, AgentEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("客服机器人未初始化，请执行数据库迁移"));
    }

    /**
     * 客服上游基地址 + API Key（均由管理端配置）。缺任一则给出可照做的中文提示。
     */
    public PlatformCredential requirePlatformCredential(SupportBotConfigEntity config) {
        String baseUrl = config.getPlatformBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new InvalidRequestException(
                    "客服尚未配置请求基地址，请到管理端「站内客服 → 客服机器人」填写后保存");
        }
        String key = config.getPlatformApiKey();
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException(
                    "客服尚未配置平台 API Key，请到管理端「站内客服 → 客服机器人」填写后保存");
        }
        return new PlatformCredential(baseUrl.strip(), key.strip());
    }

    public record PlatformCredential(String baseUrl, String apiKey) {
    }

    public SupportBotConfigEntity requireConfig(Long siteId) {
        if (siteId != null) {
            return configs.findFirstBySiteId(siteId)
                    .or(configs::findFirstByOrderByIdAsc)
                    .orElseGet(() -> createDefault(siteId));
        }
        return configs.findFirstByOrderByIdAsc()
                .orElseGet(() -> createDefault(null));
    }

    @Transactional
    protected SupportBotConfigEntity createDefault(Long siteId) {
        SupportBotConfigEntity entity = new SupportBotConfigEntity();
        entity.setSiteId(siteId);
        entity.setAgentSn(SupportConstants.AGENT_SN);
        return configs.save(entity);
    }

    public boolean isOnlineNow(SupportBotConfigEntity config) {
        if (!SupportConstants.ONLINE_SCHEDULED.equalsIgnoreCase(config.getOnlineMode())) {
            return true;
        }
        String json = config.getScheduleJson();
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            Map<String, Object> schedule = objectMapper.readValue(json, new TypeReference<>() {
            });
            ZoneId zone = ZoneId.of(config.getTimezone() == null ? "Asia/Shanghai" : config.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zone);
            @SuppressWarnings("unchecked")
            List<String> days = (List<String>) schedule.getOrDefault("days", List.of());
            String start = String.valueOf(schedule.getOrDefault("start", "09:00"));
            String end = String.valueOf(schedule.getOrDefault("end", "18:00"));
            if (!days.isEmpty()) {
                String today = now.getDayOfWeek().name().substring(0, 3);
                boolean dayOk = days.stream().anyMatch(day ->
                        day != null && day.toUpperCase(Locale.ROOT).startsWith(today.substring(0, 2)));
                // Accept MON/MONDAY style
                if (!dayOk) {
                    dayOk = days.stream().anyMatch(day -> matchesDay(day, now.getDayOfWeek()));
                }
                if (!dayOk) {
                    return false;
                }
            }
            LocalTime startTime = LocalTime.parse(start.length() == 5 ? start : start.substring(0, 5));
            LocalTime endTime = LocalTime.parse(end.length() == 5 ? end : end.substring(0, 5));
            LocalTime current = now.toLocalTime();
            if (endTime.isAfter(startTime)) {
                return !current.isBefore(startTime) && current.isBefore(endTime);
            }
            return !current.isBefore(startTime) || current.isBefore(endTime);
        } catch (Exception exception) {
            return true;
        }
    }

    private static boolean matchesDay(String raw, DayOfWeek day) {
        if (raw == null) {
            return false;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return value.equals(day.name())
                || value.equals(day.name().substring(0, 3))
                || value.startsWith(String.valueOf(day.getValue()));
    }

    private SupportBotConfigResponse toConfigResponse(SupportBotConfigEntity config,
                                                      AgentEntity agent,
                                                      AgentResponse detail) {
        boolean keySet = config.getPlatformApiKey() != null && !config.getPlatformApiKey().isBlank();
        return new SupportBotConfigResponse(
                config.getId(),
                config.getSiteId(),
                config.getAgentSn(),
                agent.getId(),
                config.isEnabled(),
                config.getOnlineMode(),
                config.getTimezone(),
                config.getScheduleJson(),
                config.getWelcomeTitle(),
                config.getWelcomeDescription(),
                config.getHomeTitle(),
                config.getBrandName(),
                config.getBrandColor(),
                config.getPlatformBaseUrl() == null ? "" : config.getPlatformBaseUrl(),
                keySet,
                detail.systemPrompt() == null ? "" : detail.systemPrompt(),
                detail.chatPrologue() == null ? "" : detail.chatPrologue(),
                detail.name(),
                detail.iconUrl() == null ? "" : detail.iconUrl(),
                detail.modelIds() == null ? List.of() : detail.modelIds(),
                detail.defaultModelId());
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            throw new InvalidRequestException("请求基地址必须以 http:// 或 https:// 开头");
        }
        return value;
    }

    private SupportFaqItemResponse toFaqItem(SupportFaqEntity faq) {
        return new SupportFaqItemResponse(
                faq.getId(),
                faq.getQuestion(),
                faq.getAnswer(),
                faq.getKeywords() == null ? "" : faq.getKeywords(),
                faq.isEnabled(),
                faq.getSortOrder());
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip();
    }
}
