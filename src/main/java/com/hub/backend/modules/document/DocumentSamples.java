package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The four samples offered in the left sidebar.
 *
 * <p>They exist so every tool can be tried without a real file. The text is generated here rather
 * than shipped as a binary: a sample is an ordinary document row with {@code fileId = null}, which
 * means the AI tools, the chat and the export all run through exactly the same code paths as an
 * uploaded file.</p>
 */
final class DocumentSamples {

    private static final Map<String, Sample> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("PDF", new Sample(
                "示例-项目方案书.pdf", "pdf", "PDF", 12, """
                项目方案书：企业文档处理平台建设

                一、项目背景
                公司现有文档处理流程依赖人工，单个项目的资料整理平均耗时 30 分钟以上。格式不统一、
                版本混乱、关键信息散落在附件里，导致评审会前反复返工。2025 年内部审计显示，
                因文档处理延误造成的项目延期占总延期的 37%。

                二、项目目标
                1. 把单份文档的处理时间从 30 分钟压缩到 1 分钟以内；
                2. 统一 PDF、Word、Excel、TXT 四类格式的入库与导出标准；
                3. 建立可检索的文档正文库，支持按内容而不是文件名查找。

                三、实施方案
                第一阶段（第 1 个月）：原型。完成上传、解析、正文抽取三个环节，覆盖 PDF 与 Word。
                第二阶段（第 2 个月）：内测。接入 AI 总结、摘要、翻译与问答，20 人试点。
                第三阶段（第 3 个月）：全量。补齐 Excel 与批量任务，推广到全部业务线。

                四、里程碑与预算
                预算总额 86 万元。其中研发人力 53.3 万元（62%），算力与模型调用 18.1 万元（21%），
                存储与带宽 8.6 万元（10%），培训与推广 6 万元（7%）。
                关键节点：第 4 周完成原型评审，第 8 周完成内测复盘，第 12 周正式上线。

                五、风险与应对
                1. 扫描件识别准确率不足：先做 OCR 抽样评估，识别率低于 90% 的文档转人工处理；
                2. 敏感数据合规：正文入库前做脱敏规则匹配，合同类文档默认不上传；
                3. 批量任务排队时延：按部门划分队列权重，夜间批量任务单独调度。

                六、验收标准
                抽取字段准确率不低于 94%，单份文档平均处理时长不超过 30 秒，
                用户满意度调研不低于 4.2 分（5 分制）。
                """));

        SAMPLES.put("Word", new Sample(
                "示例-会议纪要.docx", "docx", "Word", null, """
                产品周会会议纪要

                时间：2026 年 11 月 3 日 14:00-15:30
                地点：三层会议室 / 线上同步
                主持：产品负责人
                参会：研发、设计、运营、客服各一名代表

                一、上周进展
                1. 文档处理页完成上传、格式识别与工具区改版，本周进入联调；
                2. 论文助手修复了导出 Word 时脚注丢失的问题，已发布；
                3. 客服侧收集到 18 条关于「上传后没有反馈」的反馈，集中在超过 10MB 的文件。

                二、本次讨论
                议题一：文档处理是否要接后端真实处理。
                结论：要接。当前页面全部是前端模拟，用户点「总结」得到的是一段固定文案，
                一旦对外发布就是明显的信任问题。先接上传解析、AI 总结与文档问答三条主链路，
                格式转换类工具标注「待上线」，不要假装能用。

                议题二：上传体积上限。
                结论：单文件维持 30MB。超过 10MB 的前端要给进度提示，避免用户以为卡死。

                议题三：结果如何保存。
                结论：本期不做历史记录，结果只在当前页面，支持复制与下载；下期再考虑入库。

                三、待办事项
                · 研发：本周五前完成上传接口与正文抽取联调（负责人：后端）
                · 研发：AI 动作接入统一模型路由，失败要有可读的错误提示（负责人：后端）
                · 设计：结果区加大字号与行距，长文可读性优先（负责人：设计）
                · 运营：整理 10 份真实文档做准确率抽样（负责人：运营）

                四、下次会议
                11 月 10 日同一时间，重点评审联调结果与抽样准确率。
                """));

        SAMPLES.put("Excel", new Sample(
                "示例-数据报表.xlsx", "xlsx", "Excel", null, """
                【月度销售明细】
                区域	月份	销售额	订单数	客单价	同比
                华东	2026-07	1286400	412	3122	12.4%
                华东	2026-08	1342800	428	3137	8.6%
                华东	2026-09	1198600	386	3105	-3.2%
                华北	2026-07	964200	318	3032	6.1%
                华北	2026-08	1002800	331	3030	4.2%
                华北	2026-09	921400	302	3051	-1.8%
                华南	2026-07	842600	286	2946	18.7%
                华南	2026-08	908400	301	3018	15.2%
                华南	2026-09	886200	294	3014	9.4%
                西部	2026-07	412800	158	2613	2.3%
                西部	2026-08	436200	164	2660	1.1%
                西部	2026-09	398400	151	2638	-4.6%

                【说明】
                销售额单位为元，已含税。同比为相对去年同期。西部区域 9 月数据缺 3 天，
                统计口径与其它区域不一致，分析时需注意。华东 9 月下滑主要来自两个大客户延期提货。
                """));

        SAMPLES.put("TXT", new Sample(
                "示例-技术文档.txt", "txt", "TXT", null, """
                文档解析服务技术说明

                1. 支持的格式
                当前支持 PDF、DOCX、XLSX、TXT、Markdown、CSV。旧版 .doc / .xls 不直接支持，
                需要先另存为新格式：这两类二进制格式的解析失败率高，且容易读出乱码，
                与其给出错误结果，不如明确提示用户转换。

                2. 正文抽取
                PDF 使用 PDFBox 逐页抽取文本层。扫描件没有文本层，抽取结果为空，
                这种情况直接报错，不做 OCR——静默返回空内容会让用户以为文档没内容。
                DOCX 使用 POI 的 XWPFWordExtractor，保留段落换行但不保留样式。
                XLSX 按工作表逐行读取，单元格用制表符分隔，便于后续做表格理解。
                TXT 优先按 UTF-8 解码，失败时回退 GBK，兼容国内旧软件导出的文件。

                3. 文本规范化
                统一换行为换行符，去掉 BOM 与不可见控制字符，合并连续空行。
                PDF 抽取常在每行之间插入空行，不处理的话提示词会浪费大量预算在空白上。

                4. 限制
                单文件 30MB。正文入库上限 200 万字符，超出部分截断。
                超过 1.6 万字符的文档在调用模型时只送开头与结尾，中间标注省略字数，
                并在结果里提示「仅基于节选」。

                5. 错误处理
                解析失败要能说清是哪一步失败：加密 PDF、无文本层、格式不匹配、编码无法识别，
                这四种分别给不同的提示，不要统一显示「文件解析失败」。
                """));
    }

    private DocumentSamples() {
    }

    static Sample require(String format) {
        if (format == null || format.isBlank()) {
            throw new InvalidRequestException("缺少示例文档类型");
        }
        Sample sample = SAMPLES.get(format.strip());
        if (sample == null) {
            // Accept the file extension too, the page sends the format label but curl users send ext.
            String key = format.strip().toLowerCase(Locale.ROOT);
            sample = SAMPLES.values().stream()
                    .filter(candidate -> candidate.extension().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new InvalidRequestException("未知的示例文档类型：" + format));
        }
        return sample;
    }

    /** @param text already normalised, stored verbatim */
    record Sample(String fileName, String extension, String format, Integer pageCount, String text) {
    }
}
