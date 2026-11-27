package com.hub.backend.modules.model.entity;

// 放在实体同包：LlmModelEntity 的构造器是 protected，测试需要直接构造样本。
import com.hub.backend.modules.thesis.ThesisCatalogService;

import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The thesis model list must mirror the site's 「模型对话」 catalogue.
 *
 * <p>The regression this guards against is subtle: both screens read the same table, so they look
 * consistent at a glance, but the thesis endpoint used to flatten the list and drop the family and
 * capability fields — the same four DeepSeek models appeared in both places, one grouped by vendor
 * with 「思考」 tags and one as a bare list. Anything that starts building the list from a different
 * source or dropping fields again will fail here.</p>
 */
class ThesisCatalogModelOptionsTest {

    private static LlmModelEntity model(String key, String name, String company, String type,
                                       boolean thinking, boolean free) {
        LlmModelEntity entity = new LlmModelEntity();
        entity.setModelKey(key);
        entity.setModelName(name);
        entity.setCompany(company);
        entity.setModelType(type);
        entity.setThinking(thinking);
        entity.setFree(free);
        entity.setStatus(LlmModelEntity.STATUS_ACTIVE);
        return entity;
    }

    private ThesisCatalogService serviceWith(List<LlmModelEntity> models) {
        LlmModelRepository repository = mock(LlmModelRepository.class);
        when(repository.findVisible(any(), anyString())).thenReturn(models);
        return new ThesisCatalogService(repository);
    }

    @Test
    void onlyChatModelsAreOffered() {
        ThesisCatalogService service = serviceWith(List.of(
                model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek", "CHAT", true, false),
                model("gpt-image-2", "GPT Image 2", "OpenAI", "IMAGE", false, false)));

        ThesisCatalogResponse catalog = service.catalog(1L, true);

        assertThat(catalog.models()).extracting(ThesisCatalogResponse.ModelOption::key)
                .containsExactly("deepseek-v4.1-flash");
    }

    @Test
    void familyAndCapabilityFieldsAreCarriedThrough() {
        ThesisCatalogService service = serviceWith(List.of(
                model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek", "CHAT", true, false),
                model("doubao-pro", "豆包 Pro", "字节跳动", "CHAT", false, true)));

        ThesisCatalogResponse catalog = service.catalog(1L, true);
        ThesisCatalogResponse.ModelOption deepseek = catalog.models().get(0);
        ThesisCatalogResponse.ModelOption doubao = catalog.models().get(1);

        assertThat(deepseek.familyLabel()).isEqualTo("DeepSeek");
        assertThat(deepseek.familyKey()).isEqualTo("deepseek");
        assertThat(deepseek.capability()).isEqualTo("chat");
        assertThat(deepseek.tags()).contains("思考");
        assertThat(deepseek.isDefault()).isTrue();

        // 中文厂商名没有 ASCII slug：家族 key 直接复用厂商名，不编造拼音
        assertThat(doubao.familyKey()).isEqualTo("字节跳动");
        assertThat(doubao.tags()).contains("免费");
        // 每个厂商只有一个默认变体
        assertThat(doubao.isDefault()).isTrue();
    }

    @Test
    void firstModelOfEachCompanyBecomesTheDefaultVariant() {
        ThesisCatalogService service = serviceWith(List.of(
                model("deepseek-a", "A", "DeepSeek", "CHAT", false, false),
                model("deepseek-b", "B", "DeepSeek", "CHAT", false, false),
                model("openai-c", "C", "OpenAI", "CHAT", false, false)));

        ThesisCatalogResponse catalog = service.catalog(1L, true);

        assertThat(catalog.models()).filteredOn(ThesisCatalogResponse.ModelOption::isDefault)
                .extracting(ThesisCatalogResponse.ModelOption::key)
                .containsExactly("deepseek-a", "openai-c");
    }

    @Test
    void modelsAreListedEvenWhenTheServerCannotCallThemYet() {
        ThesisCatalogService service = serviceWith(List.of(
                model("deepseek-a", "A", "DeepSeek", "CHAT", false, false)));

        // modelEnabled 与「有哪些模型」是两件事。早先把两者绑在一起，没配 key 的部署上下拉是空的，
        // 用户看到的是「这个功能没有了」，而真实情况只是「key 没配」。
        ThesisCatalogResponse catalog = service.catalog(1L, false);

        assertThat(catalog.models()).extracting(ThesisCatalogResponse.ModelOption::key)
                .containsExactly("deepseek-a");
    }
}
