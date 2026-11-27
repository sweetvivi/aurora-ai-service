package com.hub.backend.infra.pptmaster.agent;

/**
 * Request for Claude Agent / legacy worker generation.
 */
public record AgentGenerateRequest(
        String jobId,
        String title,
        String content,
        Integer nSlides,
        String language,
        String scene,
        String tone,
        String detail,
        boolean autoImg,
        String imgDensity,
        String templateId,
        String aspectRatio,
        String generateMode,
        String deckTemplateId,
        String deckTemplateName,
        String deckStyleBlurb,
        String deckSpecJson,
        String deckWorkspacePath,
        String beautyStageDir,
        LlmConfig llm,
        ImageLlmConfig imageLlm,
        Long siteId
) {
    public record LlmConfig(String baseUrl, String model, String apiKey, String chatPath) {
    }

    public record ImageLlmConfig(String baseUrl, String apiKey, String model, String imagesPath) {
    }
}
