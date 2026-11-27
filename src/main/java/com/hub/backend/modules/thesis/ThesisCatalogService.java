package com.hub.backend.modules.thesis;

import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse.DisciplineOption;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse.ModelOption;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse.EducationOption;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse.OutlineDepthOption;
import com.hub.backend.modules.thesis.dto.ThesisCatalogResponse.WordOption;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Static form options for the thesis workbench.
 *
 * <p>Kept in code rather than in a table: the set changes a few times a year, it is identical for
 * every site, and the outline templates are keyed off exactly these values. A table would add a
 * migration and a cache without buying anything.</p>
 *
 * <p>The workbench form is ordered the way the reference product orders it — education level,
 * then outline depth, then word count — because the first two decide the skeleton and the third
 * only decides how much text each heading gets.</p>
 */
@Service
public class ThesisCatalogService {

    /** Internal template selector. Derived from the education level, never sent by the client. */
    public static final String TIER_UNDERGRAD = "UNDERGRAD";
    public static final String TIER_MASTER = "MASTER";
    public static final String TIER_JOURNAL = "JOURNAL";
    public static final String TIER_PROPOSAL = "PROPOSAL";

    /** Extra depth level used by doctoral work: the same skeleton, one more branch in chapter three. */
    public static final String TIER_DOCTOR = "DOCTOR";

    private static final int DEPTH_TWO = 2;
    private static final int DEPTH_THREE = 3;

    /** Ordered so the JSON keeps the discipline tabs in a stable order. */
    private static final Map<String, List<String>> DISCIPLINES = new LinkedHashMap<>();

    static {
        DISCIPLINES.put("工学", List.of("计算机科学与技术", "软件工程", "机械工程", "电子信息工程",
                "土木工程", "自动化", "材料科学与工程"));
        DISCIPLINES.put("理学", List.of("数学与应用数学", "统计学", "物理学", "化学", "生物科学",
                "地理信息科学"));
        DISCIPLINES.put("经济学", List.of("金融学", "国际经济与贸易", "财政学", "经济学", "数字经济"));
        DISCIPLINES.put("管理学", List.of("工商管理", "会计学", "市场营销", "人力资源管理", "物流管理",
                "公共事业管理"));
        DISCIPLINES.put("文学", List.of("汉语言文学", "新闻学", "英语", "广告学", "网络与新媒体"));
        DISCIPLINES.put("医学", List.of("临床医学", "护理学", "药学", "公共卫生与预防医学", "医学影像学"));
        DISCIPLINES.put("法学", List.of("法学", "社会工作", "知识产权", "思想政治教育"));
        DISCIPLINES.put("教育学", List.of("小学教育", "学前教育", "教育技术学", "体育教育", "心理学"));
        DISCIPLINES.put("艺术学", List.of("视觉传达设计", "环境设计", "数字媒体艺术", "音乐学", "播音与主持艺术"));
    }

    /**
     * Eight education levels, each pinned to a template and a default depth.
     * 专科 / 本科 default to a two level outline because their papers are short enough that a third
     * level turns into a list of one-line headings; 硕士 / 博士 default to three.
     */
    private static final List<EducationOption> EDUCATIONS = List.of(
            new EducationOption("专科在读", TIER_UNDERGRAD, DEPTH_TWO, 5000),
            new EducationOption("专科毕业", TIER_UNDERGRAD, DEPTH_THREE, 8000),
            new EducationOption("本科在读", TIER_UNDERGRAD, DEPTH_THREE, 8000),
            new EducationOption("本科毕业", TIER_UNDERGRAD, DEPTH_THREE, 10000),
            new EducationOption("硕士在读", TIER_MASTER, DEPTH_THREE, 15000),
            new EducationOption("硕士毕业", TIER_MASTER, DEPTH_THREE, 20000),
            new EducationOption("博士在读", TIER_DOCTOR, DEPTH_THREE, 20000),
            new EducationOption("博士毕业", TIER_DOCTOR, DEPTH_THREE, 25000));

    private static final List<OutlineDepthOption> OUTLINE_DEPTHS = List.of(
            new OutlineDepthOption(DEPTH_TWO, "二级大纲", "适用于篇幅较短,主题集中的简单论文"),
            new OutlineDepthOption(DEPTH_THREE, "三级大纲", "适用于篇幅长,主题复杂的深入研究"));

    /**
     * Word ranges rather than single numbers: the reference product sets expectations in ranges and
     * a student picking "8000-10000字" is really saying "usual undergraduate thesis". The stored
     * value is the upper bound, which is what the outline budget is computed against.
     */
    private static final List<WordOption> WORD_OPTIONS = List.of(
            new WordOption(2000, "1000-2000字", "短篇课程论文"),
            new WordOption(3000, "2000-3000字", "课程论文 / 实训报告"),
            new WordOption(5000, "3000-5000字", "专科毕业论文"),
            new WordOption(8000, "5000-8000字", "本科期末 / 期刊短文"),
            new WordOption(10000, "8000-10000字", "本科毕业论文"),
            new WordOption(15000, "10000-15000字", "优秀本科 / 硕士课程论文"),
            new WordOption(20000, "15000-20000字", "硕士学位论文"),
            new WordOption(25000, "20000字以上", "博士 / 深度研究"));

    private final LlmModelRepository models;

    public ThesisCatalogService(LlmModelRepository models) {
        this.models = models;
    }

    /**
     * Chat models shaped like the site's model catalogue.
     *
     * <p>Family grouping is derived from the provider company because that is what the catalogue
     * screen groups by; a model with no company falls into a single 「其它」 bucket rather than
     * producing one fake family per row.</p>
     */
    private List<ModelOption> chatModelOptions(Long siteId) {
        List<LlmModelEntity> visible = models.findVisible(siteId, LlmModelEntity.STATUS_ACTIVE).stream()
                .filter(model -> LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType()))
                .toList();

        // Default variant per company: the first active model, which is what the site's picker does
        // when it has no explicit flag. Recorded so the thesis picker can pre-select the same one.
        Map<String, String> defaultByCompany = new LinkedHashMap<>();
        for (LlmModelEntity model : visible) {
            defaultByCompany.putIfAbsent(companyOf(model), model.getModelKey());
        }

        return visible.stream()
                .map(model -> {
                    String company = companyOf(model);
                    return new ModelOption(
                            model.getModelKey(),
                            model.getModelName(),
                            model.getCompany(),
                            familyKeyOf(company),
                            company,
                            "chat",
                            model.isThinking(),
                            model.getDescription(),
                            tagsOf(model),
                            model.getModelKey().equals(defaultByCompany.get(company)));
                })
                .toList();
    }

    private static String companyOf(LlmModelEntity model) {
        return model.getCompany() == null || model.getCompany().isBlank()
                ? "其它"
                : model.getCompany().strip();
    }

    /** Stable, lowercase family key: 「字节跳动」 has no ASCII slug, so the company name is reused. */
    private static String familyKeyOf(String company) {
        String lower = company.toLowerCase(Locale.ROOT);
        return lower.matches("[a-z0-9 ._-]+") ? lower.replace(' ', '-') : company;
    }

    /** Tags shown next to the model name; derived from capability flags so nothing is invented. */
    private static List<String> tagsOf(LlmModelEntity model) {
        List<String> tags = new ArrayList<>();
        if (model.isThinking()) {
            tags.add("思考");
        }
        if (model.isSupportsVision()) {
            tags.add("视觉");
        }
        if (model.isSupportsTools()) {
            tags.add("工具");
        }
        if (model.isFree()) {
            tags.add("免费");
        }
        return List.copyOf(tags);
    }

    /**
     * Full form options.
     *
     * @param siteId       site of the caller, used to filter the model list by the same visibility
     *                     rule the catalogue endpoint applies
     * @param modelEnabled whether the server can actually reach a model; the client uses it to
     *                     decide between "AI 生成" and "模板生成" wording
     */
    public ThesisCatalogResponse catalog(Long siteId, boolean modelEnabled) {
        // Same source as the site's 「模型对话」 catalogue (`GET /models/families`): the same table,
        // the same visibility rule, the same capability filter. Building this list from anywhere
        // else is how the two screens drift apart — one shows DeepSeek / OpenAI / 字节跳动 grouping
        // Always published, even when the server cannot call a model right now.
        //
        // `modelEnabled` answers 「这台机器现在能不能真的调到模型」, while this list answers
        // 「后台配了哪些模型」. Coupling them meant the picker was empty on a deployment that simply
        // lacked a key, which reads as "the feature is missing" rather than "the key is missing".
        // The client still shows the list and lets the engine be switched; the flags decide wording.
        List<ModelOption> modelOptions = chatModelOptions(siteId);

        List<DisciplineOption> disciplines = DISCIPLINES.entrySet().stream()
                .map(entry -> new DisciplineOption(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        // Paper types carry the structure each kind of document should have: the label alone used to
        // leave "教学设计" and "实训报告" generating identical chapters under the same tier.
        List<ThesisCatalogResponse.PaperTypeOption> paperTypes =
                java.util.Arrays.stream(PaperTypeProfile.values())
                        .map(profile -> new ThesisCatalogResponse.PaperTypeOption(
                                profile.label(),
                                profile.label(),
                                profile.description(),
                                profile.tier(),
                                profile.keywords()))
                        .toList();

        return new ThesisCatalogResponse(EDUCATIONS, OUTLINE_DEPTHS, paperTypes, disciplines, WORD_OPTIONS,
                modelEnabled, modelOptions);
    }

    /** @return true when {@code discipline} is a known first level discipline */
    public boolean hasDiscipline(String discipline) {
        return discipline != null && DISCIPLINES.containsKey(discipline);
    }

    /** @return true when {@code major} is listed under {@code discipline} */
    public boolean hasMajor(String discipline, String major) {
        return DISCIPLINES.getOrDefault(discipline, List.of()).contains(major);
    }

    /** First major of a discipline, used when a client sends only the discipline. */
    public Optional<String> firstMajor(String discipline) {
        List<String> majors = DISCIPLINES.get(discipline);
        return majors == null || majors.isEmpty() ? Optional.empty() : Optional.of(majors.get(0));
    }

    public Optional<EducationOption> education(String label) {
        if (label == null) {
            return Optional.empty();
        }
        return EDUCATIONS.stream().filter(option -> option.label().equals(label)).findFirst();
    }

    /**
     * Template selector for an education level.
     *
     * <p>An unknown label falls back to the undergraduate skeleton instead of failing: the label is
     * cosmetic, and a template mismatch is far less annoying than a rejected request.</p>
     */
    public String tierForEducation(String educationLevel) {
        return education(educationLevel).map(EducationOption::tier).orElse(TIER_UNDERGRAD);
    }

    /** Degree is inferred from the education label so the outline can say "硕士学位论文". */
    public boolean isPostgraduate(String educationLevel) {
        return education(educationLevel)
                .map(option -> TIER_MASTER.equals(option.tier()) || TIER_DOCTOR.equals(option.tier()))
                .orElse(false);
    }

    public int defaultDepth(String educationLevel) {
        return education(educationLevel).map(EducationOption::defaultDepth).orElse(DEPTH_THREE);
    }

    public int defaultWords(String educationLevel) {
        return education(educationLevel).map(EducationOption::defaultWords).orElse(8000);
    }

    /** Kept for callers that still work in tiers. */
    public int defaultWordsForTier(String tier) {
        return TIER_MASTER.equals(tier) || TIER_DOCTOR.equals(tier) ? 20000 : 8000;
    }
}
