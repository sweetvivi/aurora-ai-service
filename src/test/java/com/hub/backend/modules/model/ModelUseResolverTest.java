package com.hub.backend.modules.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「没人指定模型时跑哪个」这条规则的回归测试。
 *
 * <p>2026-10-29 电商生成整片失败：运营把 {@code deepseek-v4.1-flash} 换成
 * {@code deepseek-v4-1-flash-260910}，而 compose 链路还把静态配置当兜底，于是每一单都回
 * {@code Model 'deepseek-v4.1-flash' is not available}。这里锁住三件事：站点设置优先、
 * 退役键必须被替换、兜底值不能来自静态属性。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelUseResolverTest {

    private static final Long SITE_ID = 1L;
    private static final String RETIRED = "deepseek-v4.1-flash";
    private static final String CURRENT = "deepseek-v4-1-flash-260910";

    @Mock
    private LlmModelRepository models;
    @Mock
    private AppSiteSettingRepository settings;

    private final LlmProperties llmProperties = new LlmProperties();
    private ModelUseResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ModelUseResolver(models, new ObjectMapper(), settings, llmProperties);
        when(settings.findBySiteIdOrderBySettingKeyAsc(SITE_ID)).thenReturn(List.of());
        llmProperties.setDefaultModel(RETIRED);
    }

    @Test
    void keepsTheSiteSettingWhileItIsStillListed() {
        defaultModelSetting(CURRENT);
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(chatModel(CURRENT)));

        assertThat(resolver.defaultModelForSite(SITE_ID)).isEqualTo(CURRENT);
    }

    @Test
    void replacesARetiredDefaultWithTheFirstAvailableChatModel() {
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(imageModel("gpt-image-2.5"), chatModel(CURRENT)));

        assertThat(resolver.defaultModelForSite(SITE_ID)).isEqualTo(CURRENT);
    }

    @Test
    void keepsTheConfiguredValueBeforeTheCatalogueHasLoaded() {
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE)).thenReturn(List.of());

        assertThat(resolver.defaultModelForSite(SITE_ID)).isEqualTo(RETIRED);
    }

    @Test
    void resolveForSiteNeverFallsBackToTheStaticProperty() {
        defaultModelSetting(CURRENT);
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(chatModel(CURRENT)));
        when(models.findVisibleByModelKeyForResolution(SITE_ID, CURRENT))
                .thenReturn(List.of(chatModel(CURRENT)));

        ModelUseResolver.Resolved resolved =
                resolver.resolveForSite(SITE_ID, null, LlmModelEntity.TYPE_CHAT);

        assertThat(resolved.upstreamKey()).isEqualTo(CURRENT);
        // 关键断言：退役的静态键一次都不该被查
        verify(models, never()).findVisibleByModelKeyForResolution(SITE_ID, RETIRED);
    }

    @Test
    void explicitModelIsUsedWithoutReadingTheSiteDefault() {
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "deepseek-v4-1-flash-261017"))
                .thenReturn(List.of(chatModel("deepseek-v4-1-flash-261017")));

        ModelUseResolver.Resolved resolved = resolver.resolveForSite(
                SITE_ID, "deepseek-v4-1-flash-261017", LlmModelEntity.TYPE_CHAT);

        assertThat(resolved.upstreamKey()).isEqualTo("deepseek-v4-1-flash-261017");
        verify(settings, never()).findBySiteIdOrderBySettingKeyAsc(any());
    }

    @Test
    void explainsWhatToFixWhenEvenTheDefaultIsUnusable() {
        // 目录里一个对话模型都没有 → 保留配置值 → 该值在库里是停用状态
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE)).thenReturn(List.of());
        LlmModelEntity retired = chatModel(RETIRED);
        retired.setStatus("DISABLED");
        when(models.findVisibleByModelKeyForResolution(SITE_ID, RETIRED))
                .thenReturn(List.of(retired));

        assertThatThrownBy(() -> resolver.resolveForSite(SITE_ID, null, LlmModelEntity.TYPE_CHAT))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("默认模型")
                .hasMessageContaining(RETIRED)
                .hasMessageContaining("重新指定");
    }

    @Test
    void keepsTheConfiguredImageModelWhileItIsListed() {
        llmProperties.setImageModel("gpt-image-2");
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(imageModel("gpt-image-2")));

        assertThat(resolver.defaultModelForSite(SITE_ID, LlmModelEntity.TYPE_IMAGE))
                .isEqualTo("gpt-image-2");
        verify(models, never()).findVisibleByModelKeyForResolution(any(), any());
    }

    @Test
    void replacesARetiredImageModelWithOneFromTheSameSupplier() {
        // 静态配置仍指向 gpt-image-2，目录里换成了同 supplier 的新行。
        llmProperties.setImageModel("gpt-image-2");
        LlmModelEntity old = imageModel("gpt-image-2");
        old.setProviderId(11L);
        LlmModelEntity sameSupplier = imageModel("gpt-image-3");
        sameSupplier.setProviderId(11L);
        LlmModelEntity otherSupplier = imageModel("seedream-4");
        otherSupplier.setProviderId(22L);
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(otherSupplier, sameSupplier));
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "gpt-image-2"))
                .thenReturn(List.of(old));

        assertThat(resolver.defaultImageModelForSite(SITE_ID)).isEqualTo("gpt-image-3");
    }

    @Test
    void fallsBackToTheSameCompanyBeforeAnyImageModel() {
        llmProperties.setImageModel("gpt-image-2");
        LlmModelEntity old = imageModel("gpt-image-2");
        old.setCompany("OpenAI");
        LlmModelEntity sameCompany = imageModel("dall-e-4");
        LlmModelEntity unrelated = imageModel("seedream-4");
        unrelated.setCompany("ByteDance");
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(unrelated, sameCompany));
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "gpt-image-2"))
                .thenReturn(List.of(old));

        assertThat(resolver.defaultImageModelForSite(SITE_ID)).isEqualTo("dall-e-4");
    }

    @Test
    void neverSubstitutesWhenTheCatalogueHasNoImageModel() {
        llmProperties.setImageModel("gpt-image-2");
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(chatModel(CURRENT)));

        // 只有对话模型可用时不能拿它去生图：猜错上游比原样透传更难查
        assertThat(resolver.defaultImageModelForSite(SITE_ID)).isEqualTo("gpt-image-2");
    }

    @Test
    void imageResolutionAsksForTheImageType() {
        llmProperties.setImageModel("gpt-image-2");
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "gpt-image-2"))
                .thenReturn(List.of(imageModel("gpt-image-2")));

        ModelUseResolver.Resolved resolved =
                resolver.resolveForSite(SITE_ID, null, LlmModelEntity.TYPE_IMAGE);

        assertThat(resolved.upstreamKey()).isEqualTo("gpt-image-2");
        assertThat(resolved.modelType()).isEqualTo(LlmModelEntity.TYPE_IMAGE);
        // 图片默认值不该去读对话用的站点设置
        verify(settings, never()).findBySiteIdOrderBySettingKeyAsc(SITE_ID);
    }

    @Test
    void refusesToGuessADefaultForTypesThatHaveNone() {
        // 拿对话模型顶到 VIDEO 上只会换来一个 type 不匹配的 400，不如在这里说清楚
        assertThatThrownBy(() -> resolver.defaultModelForSite(SITE_ID, LlmModelEntity.TYPE_VIDEO))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("显式指定");
    }

    /**
     * 目录为空时不能拒。空目录意味着「目录还不可信」（首次部署、同步失败、两次同步之间），
     * 此时表里一个模型都没有，任何 key 都算未登记 —— 严格拒绝会把生图、修图、画布、对话
     * 一起打成 404，而网关其实是可用的。2026-10-29 同步后 SmartEdit/AiCanvas 共 14 例
     * 从 200/400/503 变成 404，就是这个原因。
     */
    @Test
    void forwardsAnUnlistedKeyWhileTheCatalogueHasNothingOfThatType() {
        when(models.countByTypeForResolution(LlmModelEntity.TYPE_VIDEO)).thenReturn(0L);

        ModelUseResolver.Resolved resolved = resolver.resolve(
                SITE_ID, "doubao-seedance-2-0", null, LlmModelEntity.TYPE_VIDEO);

        assertThat(resolved.upstreamKey()).isEqualTo("doubao-seedance-2-0");
        assertThat(resolved.listed()).isFalse();
    }

    /** 目录里已经有同类模型 → 目录可作准，未登记的 key 按开关拒绝（默认 false）。 */
    @Test
    void rejectsAnUnlistedKeyOnceTheCatalogueKnowsThatType() {
        when(models.countByTypeForResolution(LlmModelEntity.TYPE_IMAGE)).thenReturn(1L);

        assertThatThrownBy(() -> resolver.resolve(
                SITE_ID, "yunwu/gpt-image-2", null, LlmModelEntity.TYPE_IMAGE))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("yunwu/gpt-image-2");
    }

    @Test
    void countsHiddenRowsAsKnownSoAnUnlistedKeyIsStillRejected() {
        // hidden-model 被运营停用后 findVisible 查不到，但它仍是一条登记过的 CHAT 行。
        // 用它判断"目录认识这个类型"是对的：否则停用全部对话模型就等于放开透传。
        when(models.countByTypeForResolution(LlmModelEntity.TYPE_CHAT)).thenReturn(1L);

        assertThatThrownBy(() -> resolver.resolve(
                SITE_ID, "not-synced-yet", null, LlmModelEntity.TYPE_CHAT))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not-synced-yet");
    }

    /** 开关打开时保留旧逃生口：目录已同步但要用一个刚上线、还没入库的模型。 */
    @Test
    void forwardsAnUnlistedKeyWhenTheEscapeHatchIsOpen() {
        llmProperties.setAllowUnlistedModels(true);
        when(models.countByTypeForResolution(LlmModelEntity.TYPE_IMAGE)).thenReturn(1L);

        assertThat(resolver.resolve(SITE_ID, "gpt-image-3", null, LlmModelEntity.TYPE_IMAGE)
                .upstreamKey()).isEqualTo("gpt-image-3");
    }

    /** 谁都没指定模型、也没有默认值：说清楚缺配置，别报成 Model 'null' is not available。 */
    @Test
    void saysTheModelIsMissingInsteadOfEchoingNull() {
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(imageModel("gpt-image-2")));

        assertThatThrownBy(() -> resolver.resolve(
                SITE_ID, null, null, LlmModelEntity.TYPE_IMAGE))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("默认绘图模型")
                .hasMessageNotContaining("null");
    }

    private void defaultModelSetting(String value) {
        AppSiteSettingEntity setting = mock(AppSiteSettingEntity.class);
        when(setting.getSettingKey()).thenReturn(AppSiteSettingEntity.KEY_DEFAULT_MODEL);
        when(setting.getSettingValue()).thenReturn(value);
        when(settings.findBySiteIdOrderBySettingKeyAsc(SITE_ID)).thenReturn(List.of(setting));
    }

    /**
     * 组 key 也是合法的默认值。
     *
     * <p>把 {@code LLM_IMAGE_MODEL} 配成回退组 key 时，以前会被当成"已退役的具体模型"，
     * 白白替换成某个具体 key —— 于是 {@code ImageModelFallbackService} 只拿到一个候选，
     * 组里配多少成员都不生效，而且只影响"调用方没指定模型"的路径（抠图/超清/消除/重绘）。</p>
     */
    @Test
    void keepsAnImageGroupKeyAsTheDefaultSoTheFallbackChainStaysReachable() {
        String groupKey = "gpt-image-2.0";
        llmProperties.setImageModel(groupKey);
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(imageModel("gpt-image-2")));

        ModelFallbackCatalogService catalog = mock(ModelFallbackCatalogService.class);
        // 静默探测走独立事务（REQUIRES_NEW）：同一个"组存在但不可用"的异常参与外层只读事务时
        // 会把外层标成 rollback-only，最后在 commit 抛 Transaction silently rolled back。
        when(catalog.findUsableGroupQuietly(SITE_ID, groupKey, LlmModelEntity.TYPE_IMAGE))
                .thenReturn(java.util.Optional.of(new ModelFallbackCatalogService.GroupView(
                        new LlmModelFallbackGroupEntity(
                                SITE_ID, groupKey, "GPT-Image-2.0", LlmModelEntity.TYPE_IMAGE),
                        List.of())));
        ModelUseResolver withGroups =
                new ModelUseResolver(models, new ObjectMapper(), settings, llmProperties, catalog);

        assertThat(withGroups.defaultImageModelForSite(SITE_ID)).isEqualTo(groupKey);
        // 不能被当成退役模型去查替代品
        verify(models, never()).findVisibleByModelKeyForResolution(any(), any());
    }

    @Test
    void substitutesWhenTheConfiguredImageKeyIsNeitherAModelNorAGroup() {
        llmProperties.setImageModel("gpt-image-2.0");
        when(models.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(imageModel("gpt-image-2")));
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "gpt-image-2.0"))
                .thenReturn(List.of());

        ModelFallbackCatalogService catalog = mock(ModelFallbackCatalogService.class);
        when(catalog.findVisibleGroup(SITE_ID, "gpt-image-2.0", LlmModelEntity.TYPE_IMAGE))
                .thenReturn(java.util.Optional.empty());
        ModelUseResolver withGroups =
                new ModelUseResolver(models, new ObjectMapper(), settings, llmProperties, catalog);

        // 组也不存在时仍然要走原来的替代逻辑，不能把无效 key 直接透给上游
        assertThat(withGroups.defaultImageModelForSite(SITE_ID)).isEqualTo("gpt-image-2");
    }

    /**
     * 同一个 key 有多行时，必须选<b>可用</b>的那一行，而不是 id 最小的那一行。
     *
     * <p>2026-11-02 视频全线 404：{@code grok-imagine-video} 同时存在两行 —— 手工登记的
     * provider 14 行（id 21）和 New API 目录同步出来的 provider 15 行（id 25）。同步把 21 标成
     * {@code upstream_available=0}，而解析器按 id 升序取第一行就 return，于是列表里看得见 25、
     * 提交却答 {@code Model 'grok-imagine-video' is not available}。列表与查询对不上正是这些
     * 可见性规则要杜绝的情况。</p>
     */
    @Test
    void prefersAUsableRowWhenTheSameKeyIsRegisteredTwice() {
        LlmModelEntity retired = videoModel("grok-imagine-video");
        retired.setUpstreamAvailable(false);
        LlmModelEntity usable = videoModel("grok-imagine-video");
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "grok-imagine-video"))
                .thenReturn(List.of(retired, usable));

        ModelUseResolver.Resolved resolved = resolver.resolve(
                SITE_ID, "grok-imagine-video", null, LlmModelEntity.TYPE_VIDEO);

        assertThat(resolved.upstreamKey()).isEqualTo("grok-imagine-video");
        assertThat(resolved.listed()).isTrue();
    }

    /** 两行都不可用时，仍按第一行报错 —— 错误原因不能被吞成一个泛化的 404。 */
    @Test
    void stillReportsTheFirstRowWhenNoDuplicateIsUsable() {
        LlmModelEntity retired = videoModel("grok-imagine-video");
        retired.setUpstreamAvailable(false);
        when(models.findVisibleByModelKeyForResolution(SITE_ID, "grok-imagine-video"))
                .thenReturn(List.of(retired));

        assertThatThrownBy(() -> resolver.resolve(
                SITE_ID, "grok-imagine-video", null, LlmModelEntity.TYPE_VIDEO))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("grok-imagine-video");
    }

    private static LlmModelEntity videoModel(String modelKey) {
        LlmModelEntity model = new LlmModelEntity(modelKey, modelKey, "xAI", modelKey);
        model.setModelType(LlmModelEntity.TYPE_VIDEO);
        return model;
    }

    private static LlmModelEntity chatModel(String modelKey) {
        LlmModelEntity model = new LlmModelEntity(modelKey, modelKey, "DeepSeek", modelKey);
        model.setModelType(LlmModelEntity.TYPE_CHAT);
        return model;
    }

    private static LlmModelEntity imageModel(String modelKey) {
        LlmModelEntity model = new LlmModelEntity(modelKey, modelKey, "OpenAI", modelKey);
        model.setModelType(LlmModelEntity.TYPE_IMAGE);
        return model;
    }
}
