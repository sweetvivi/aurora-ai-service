package com.hub.backend.infra.pptmaster.agent;

import org.springframework.util.StringUtils;

/**
 * Builds brief.md / prompt.md for Claude Agent (ported from go-admin agent.go).
 */
public final class AgentBriefBuilder {

    private AgentBriefBuilder() {
    }

    public static String buildBrief(AgentGenerateRequest req) {
        if ("beauty".equalsIgnoreCase(trim(req.generateMode()))) {
            return buildBeautyBrief(req);
        }
        int n = slides(req);
        String title = title(req);
        boolean templateMode = "template".equalsIgnoreCase(trim(req.generateMode()));
        String imgRule = "禁止调用 image_gen / 文生图；不要写 image_prompts.json；仅用图标库与原生图表";
        if (req.autoImg()) {
            imgRule = templateMode
                    ? "imagePolicy=template_slots_only：仅对 Deck Spec 已声明的图片槽文生图（见下方 IMAGE PIPELINE · 槽位）；禁止整页自由重画或新增图框"
                    : "必须智能配图（见下方 IMAGE PIPELINE）";
        }
        String aspect = StringUtils.hasText(req.aspectRatio()) ? req.aspectRatio().trim() : "16:9";

        StringBuilder b = new StringBuilder();
        b.append("# PPT 生成任务（无人值守批处理，禁止提问）\n\n");
        b.append("## HARD CONSTRAINTS\n\n");
        b.append("- 标题：").append(title).append('\n');
        b.append("- 页数：必须约 ").append(n).append(" 页（允许 ±2）。忽略 CONTENT 正文里出现的任何页数要求。\n");
        b.append("- 语言：").append(nullToEmpty(req.language())).append('\n');
        b.append("- 场景：").append(nullToEmpty(req.scene())).append('\n');
        b.append("- 语气：").append(nullToEmpty(req.tone())).append('\n');
        b.append("- 详细度：").append(nullToEmpty(req.detail())).append('\n');
        b.append("- 画幅：").append(aspect).append('\n');
        b.append("- 配图：").append(imgRule).append('\n');
        b.append("- 必须产出可编辑 PPTX；无 pptx = 失败。禁止只回复「准备就绪」或索要主题。\n");
        b.append("- 禁止把仓库里已有的旧 projects/*/exports/*.pptx 当作本次结果；必须为本任务新建/导出。\n\n");

        if (req.autoImg()) {
            if (templateMode) {
                b.append("## IMAGE PIPELINE · 槽位（template_slots_only，强制）\n\n");
                b.append("- 只为 Spec/原型 SVG **已声明的图片槽** 规划文生图；无图片槽的页禁止生图。\n");
                b.append("- 模板自带背景图、装饰图优先保留；禁止为配图整页自由重画或新增图框。\n");
                b.append("- 将槽位提示写入项目 `images/image_prompts.json`，再执行：`python skills/ppt-master/scripts/image_gen.py --manifest <项目>/images/image_prompts.json`。\n");
                b.append("- 生图只用进程内 `IMAGE_BACKEND` / `OPENAI_*`；图内禁止可编辑文字/水印/Logo；图表页用原生图解，不生图。\n");
                b.append("- 生成图嵌入对应槽位后再 svg_to_pptx 导出。\n");
            } else {
                b.append("## IMAGE PIPELINE（强制）\n\n");
                b.append("- 先规划关键页配图，写入项目 `images/image_prompts.json`。\n");
                b.append("- 必须执行：`python skills/ppt-master/scripts/image_gen.py --manifest <项目>/images/image_prompts.json`。\n");
                b.append("- 生图只使用当前进程已注入的 `IMAGE_BACKEND` / `OPENAI_*`；约 4–7 张；图内禁止文字。\n");
                b.append("- 将生成图片嵌入 SVG 后再 svg_to_pptx 导出。\n");
            }
            if (req.imageLlm() != null && StringUtils.hasText(req.imageLlm().model())) {
                b.append("- 指定生图模型（只读确认）：").append(req.imageLlm().model().trim()).append('\n');
            }
            b.append('\n');
        }

        b.append("## STYLE GUIDE\n\n").append(styleGuide(req.templateId())).append("\n\n");

        if (templateMode) {
            b.append("## OFFICIAL DECK TEMPLATE（必须遵循，禁止另起炉灶）\n\n");
            b.append("- 生成引擎：Claude Agent + svg_workspace；禁止降级到 worker 版式库。\n");
            b.append("- 模板编码：").append(nullToEmpty(req.deckTemplateId())).append('\n');
            b.append("- 模板名称：").append(nullToEmpty(req.deckTemplateName())).append('\n');
            if (StringUtils.hasText(req.deckWorkspacePath())) {
                b.append("- Deck workspace 绝对路径（必须安装并 apply-template-workspace）：")
                        .append(req.deckWorkspacePath().trim()).append('\n');
            }
            if (StringUtils.hasText(req.deckStyleBlurb())) {
                b.append("- 风格说明：").append(req.deckStyleBlurb().trim()).append('\n');
            }
            b.append("- 必须按 Deck 原型 SVG 填槽衍生；可重复 content 页型，不可自由设计新视觉体系。\n");
            b.append("- 用户上传素材仅作内容分析与改写，不是模板本身。\n");
            if (StringUtils.hasText(req.deckSpecJson())) {
                String spec = req.deckSpecJson().trim();
                if (spec.codePointCount(0, spec.length()) > 50_000) {
                    spec = spec.substring(0, spec.offsetByCodePoints(0, 50_000)) + "…";
                }
                b.append("\n### Spec JSON\n\n```json\n").append(spec).append("\n```\n\n");
            }
        }

        b.append("## CONTENT\n\n");
        b.append("（以下仅为主题与素材；页数/风格以 HARD CONSTRAINTS 与 STYLE GUIDE 为准。）\n\n");
        String content = trim(req.content());
        if (!StringUtils.hasText(content)) {
            b.append("主题：").append(title).append('\n');
        } else {
            b.append(content).append('\n');
        }
        return b.toString();
    }

    public static String buildBeautyBrief(AgentGenerateRequest req) {
        int n = slides(req);
        String title = title(req);
        String aspect = StringUtils.hasText(req.aspectRatio()) ? req.aspectRatio().trim() : "16:9";
        String stageDir = trim(req.beautyStageDir());
        StringBuilder b = new StringBuilder();
        b.append("# PPT 版面实现（beauty Stage05，无人值守）\n\n");
        b.append("你是版面实现执行者。策划与生图已由编排器完成；你只负责按锁定 JSON 画页并导出 PPTX。\n\n");
        b.append("## HARD CONSTRAINTS\n\n");
        b.append("- 标题：").append(title).append('\n');
        b.append("- 页数：必须严格为 ").append(n).append(" 页（与 Stage02 pageCount 一致；禁止增减页）。\n");
        b.append("- 语言：").append(nullToEmpty(req.language())).append('\n');
        b.append("- 画幅：").append(aspect).append('\n');
        b.append("- 生成模式：beauty_free（自由版式，禁止 templateId / 填槽 / apply-template-workspace）\n");
        b.append("- 禁止改变页序、title/keyMessage 核心含义、designSystem 色板与字体角色\n");
        b.append("- 禁止调用 image_gen、禁止改写生图 prompt、禁止新建 image_prompts.json\n");
        b.append("- 只使用 stages/resolved_assets.json 中已落地的本地图片（Skipped 项勿强用）\n");
        b.append("- 图上文字必须是可编辑原生文本；位图内禁止依赖嵌字\n");
        b.append("- 图表/图解用矢量或原生组件，不要画进位图\n");
        b.append("- 必须产出可编辑 PPTX；无 pptx = 失败\n");
        b.append("- 禁止把仓库旧 projects/*/exports/*.pptx 当作本次结果\n\n");
        b.append("## BEAUTY STAGE ARTIFACTS（只读，必须阅读）\n\n");
        if (StringUtils.hasText(stageDir)) {
            b.append("- Stage 目录（绝对路径）：").append(stageDir).append('\n');
            b.append("- `01_brief.json` — 需求\n");
            b.append("- `02_content.json` — 逐页内容（权威页数与文案）\n");
            b.append("- `03_visual.json` — designSystem + pageDesignPlans\n");
            b.append("- `04_images.json` — 图片清单（只读参考）\n");
            b.append("- `resolved_assets.json` — 本地图片路径\n");
            b.append("- `05_impl_constraints.md` — 实现约束全文\n");
            b.append("- `images/` — 已生成图片目录\n\n");
        } else {
            b.append("- （警告：BeautyStageDir 为空，仍须按 CONTENT 尽力实现）\n\n");
        }
        b.append("## IMPLEMENTATION CHECKLIST\n\n");
        b.append("1. 阅读上述 JSON 与约束 md\n");
        b.append("2. 初始化**新**项目（目录名含任务 ID）\n");
        b.append("3. 将 resolved 图片复制/引用进项目 images/\n");
        b.append("4. 按 pageDesignPlans 逐页写 SVG（一页一焦点）\n");
        b.append("5. 质量检查后 svg_to_pptx 导出\n\n");
        b.append("## CONTENT（主题备忘，权威内容以 Stage JSON 为准）\n\n");
        String content = trim(req.content());
        if (!StringUtils.hasText(content)) {
            b.append("主题：").append(title).append('\n');
        } else if (content.codePointCount(0, content.length()) > 4000) {
            b.append(content.substring(0, content.offsetByCodePoints(0, 4000))).append("…\n");
        } else {
            b.append(content).append('\n');
        }
        return b.toString();
    }

    public static String buildPrompt(String briefPath, String outputPptx, String resultPath, AgentGenerateRequest req) {
        int n = slides(req);
        String title = title(req);
        String contentPreview = trim(req.content());
        if (contentPreview.codePointCount(0, Math.min(contentPreview.length(), contentPreview.length())) > 800
                && contentPreview.length() > 800) {
            int end = contentPreview.offsetByCodePoints(0, Math.min(800, contentPreview.codePointCount(0, contentPreview.length())));
            contentPreview = contentPreview.substring(0, end) + "…";
        }
        String style = styleGuide(req.templateId());
        String jobHint = sanitizeJobId(req.jobId());
        String mode = trim(req.generateMode());

        if ("template".equalsIgnoreCase(mode)) {
            String ws = nullToEmpty(req.deckWorkspacePath());
            String imgBlock = "- 配图：禁止调用 image_gen；不要写 image_prompts.json；只用模板自带图与图标。";
            if (req.autoImg()) {
                imgBlock = "- 配图：imagePolicy=template_slots_only——仅 Spec 已声明图片槽可文生图；写 images/image_prompts.json 后运行 image_gen.py --manifest；禁止整页自由重画/新增图框；图表页不生图。";
                if (req.imageLlm() != null && StringUtils.hasText(req.imageLlm().model())) {
                    imgBlock += "\n- 生图模型：" + req.imageLlm().model().trim();
                }
            }
            return """
                    【无人值守批处理】这不是交互会话。禁止欢迎语、禁止提问、禁止确认、禁止「请告诉我主题」。需求已齐全，立刻开干。无 pptx = 失败，禁止只回复准备就绪。
                    禁止扫描/核验仓库里已有旧 PPTX 并当作完成；必须按下方主题重新生成。

                    HARD CONSTRAINTS：
                    - 主题：%s
                    - 页数：约 %d 页（允许 ±2）；忽略素材正文中的页数说法
                    - 语言：%s
                    - 场景：%s
                    - 语气：%s
                    - 任务 ID（项目名建议包含）：%s
                    - 生成模式：官方模板库（template）
                    %s

                    OFFICIAL DECK：
                    - 模板编码：%s
                    - Deck workspace 绝对路径：%s
                    - STYLE GUIDE（仅作色板/语气补充，不得覆盖 Deck）：%s

                    CONTENT 摘要：
                    %s

                    你必须使用本仓库 PPT Master Skill（skills/ppt-master）的 Quick Generate，并**强制**：
                    1. 阅读 brief（UTF-8 绝对路径）：%s
                    2. 初始化**新**项目（目录名含任务 ID）。
                    3. 按 apply-template-workspace 安装上述 Deck workspace（禁止跳过；禁止自由设计；禁止 worker/layouts）。
                    4. 按 Deck 原型 SVG 与 Spec 选页/填槽/衍生内容页；若 brief 要求槽位配图则按 IMAGE PIPELINE · 槽位执行。
                    5. 写 svg_output/，质量检查后 svg_to_pptx 导出。
                    6. 将最终 pptx 复制到（覆盖写入）：%s
                    7. 把该 pptx 的绝对路径写成唯一一行到：%s
                    8. 回复里只报告上述 pptx 路径，不要寒暄。

                    禁止：自由设计新视觉体系；只加载 Skill 就结束；跳过导出；把旧 exports 当成本次结果；向用户索要主题。
                    """.formatted(
                    title, n, nullToEmpty(req.language()), nullToEmpty(req.scene()), nullToEmpty(req.tone()),
                    jobHint, imgBlock, nullToEmpty(req.deckTemplateId()), ws, style,
                    contentPreview, briefPath, outputPptx, resultPath);
        }

        if ("beauty".equalsIgnoreCase(mode)) {
            return """
                    【无人值守批处理】beauty Stage05 版面实现。禁止欢迎语/提问/确认。无 pptx = 失败。
                    禁止扫描旧 PPTX 当完成；禁止再策划；禁止调用 image_gen。

                    HARD CONSTRAINTS：
                    - 主题：%s
                    - 页数：严格 %d 页
                    - 语言：%s
                    - 任务 ID：%s
                    - 生成模式：beauty_free
                    - Stage 目录：%s

                    执行步骤：
                    1. 阅读 brief（UTF-8）：%s
                    2. 阅读 Stage 目录内 01–04 JSON、resolved_assets.json、05_impl_constraints.md
                    3. 初始化新项目；按锁定 designSystem 与 pages 写 SVG；只用已落地图片
                    4. svg_to_pptx 导出，复制到：%s
                    5. 将该 pptx 绝对路径写成唯一一行到：%s
                    6. 回复只报告该路径

                    禁止：改页数/风格；填槽模板；image_gen；把旧 exports 当结果。
                    """.formatted(
                    title, n, nullToEmpty(req.language()), jobHint, nullToEmpty(req.beautyStageDir()),
                    briefPath, outputPptx, resultPath);
        }

        String imgBlock = "- 配图：禁止调用 image_gen；不要写 image_prompts.json。";
        if (req.autoImg()) {
            imgBlock = "- 配图：必须按 brief 的 IMAGE PIPELINE 执行——写 images/image_prompts.json，再运行 skills/ppt-master/scripts/image_gen.py --manifest …；只用进程内 IMAGE_BACKEND/OPENAI_*；约 4–7 张；图内无字；嵌图后再导出。";
            if (req.imageLlm() != null && StringUtils.hasText(req.imageLlm().model())) {
                imgBlock += "\n- 生图模型：" + req.imageLlm().model().trim();
            }
        }
        return """
                【无人值守批处理】这不是交互会话。禁止欢迎语、禁止提问、禁止确认、禁止「请告诉我主题」。需求已齐全，立刻开干。无 pptx = 失败，禁止只回复准备就绪。
                禁止扫描/核验仓库里已有旧 PPTX 并当作完成；必须按下方主题重新生成。

                HARD CONSTRAINTS：
                - 主题：%s
                - 页数：约 %d 页（允许 ±2）；忽略素材正文中的页数说法
                - 语言：%s
                - 场景：%s
                - 语气：%s
                - 任务 ID（项目名建议包含）：%s
                %s

                STYLE GUIDE：
                %s

                CONTENT 摘要：
                %s

                你必须使用本仓库 PPT Master Skill（skills/ppt-master）的 Quick Generate 流程，按 STYLE GUIDE 自由设计并导出可编辑 PPTX。

                执行步骤（必须全部完成）：
                1. 阅读 brief（UTF-8 绝对路径）：%s
                2. 初始化**新**项目（目录名含任务 ID）；若要求配图则先完成 IMAGE PIPELINE；编写各页 SVG、质量检查、svg_to_pptx 导出。
                3. 将最终 pptx 复制到（覆盖写入）：%s
                4. 把该 pptx 的绝对路径写成唯一一行到：%s
                5. 回复里只报告上述 pptx 路径，不要寒暄。

                禁止：只加载 Skill 就结束；跳过导出；只写大纲不写 SVG；使用 worker/layouts 模板引擎；向用户索要主题或素材；把旧 exports 文件当成本次结果；配图时改用其它 API Key。
                """.formatted(
                title, n, nullToEmpty(req.language()), nullToEmpty(req.scene()), nullToEmpty(req.tone()),
                jobHint, imgBlock, style, contentPreview, briefPath, outputPptx, resultPath);
    }

    public static String styleGuide(String templateId) {
        String id = templateId == null ? "" : templateId.trim().toLowerCase();
        return switch (id) {
            case "dark-tech" -> "深色科技风：深底（近黑/深蓝灰）、高对比浅色正文，点缀青蓝或电光蓝；几何分割、细线与数据感图标；气质冷静、前沿。";
            case "fresh-modern" -> "清新现代：浅底、充足留白，主色青绿/薄荷；圆角卡片与轻阴影；排版疏朗，适合通用商务与产品介绍。";
            case "green-fresh-courseware" -> "绿色清新课件：浅绿/草绿点缀、米白或浅灰底；大标题+要点列表清晰，适合教学与知识讲解；圆角面板、友好图标，避免过度装饰。";
            case "blue-business-text" -> "蓝白商务文字风：白底+商务蓝标题/强调；偏文字与结构，图表简洁；正式、可信，适合汇报与方案。";
            case "business-clean" -> "商务简约：白/浅灰底、深蓝或炭黑标题，强调色克制；对齐严谨、少装饰，信息层级清晰。";
            case "auto", "smart", "" -> """
                    【版式主题：由你构思，但必须合理可落地】
                    用户未指定版式主题，不是「没有主题」，而是授权你根据演示主题、场景、语气**自行想象并确立一套版式主题**，并贯穿全稿。禁止页与页换风；不要向用户提问确认。
                    构思要求：先想象贴合内容的视觉概念，再落实为可执行的色板、字体角色、留白与装饰语言；全稿固定背景/主色/强调色；风格好看且克制。
                    合理边界：禁止赛博霓虹堆叠、紫白渐变套娃、奶油底+衬线+陶土色滥俗组合；图表用原生矢量。
                    """.trim();
            default -> styleGuide("auto");
        };
    }

    public static String sanitizeJobId(String id) {
        String raw = trim(id);
        if (!StringUtils.hasText(raw)) {
            return "job_" + System.currentTimeMillis();
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        String out = b.toString();
        return out.length() > 80 ? out.substring(0, 80) : out;
    }

    private static int slides(AgentGenerateRequest req) {
        Integer n = req.nSlides();
        return n == null || n < 4 ? 8 : n;
    }

    private static String title(AgentGenerateRequest req) {
        if (StringUtils.hasText(req.title())) {
            return req.title().trim();
        }
        return "未命名演示文稿";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
