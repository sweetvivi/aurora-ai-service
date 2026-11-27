-- ---------------------------------------------------------------------------------------------
-- 论文工作台：学历层次、文本类型、大纲层级
--
-- 背景：参考站的工作台把「学历层次」拆成 8 档（专科/本科/硕士/博士 × 在读/毕业），并把
-- 「大纲结构」做成二级 / 三级二选一。原来的 tier 只有一个模糊的四档，前端为了对齐界面已经
-- 不得不在本地拼字符串。这里把两个维度落成正式字段：
--
--   education_level  展示用的学历标签，同时决定模板（内部仍写入 tier 列，旧数据不受影响）
--   paper_type       文本类型（论文助手 / 开题报告 / 期刊文章 …），只影响措辞与导出命名
--   outline_depth    2 二级 / 3 三级；保存时按实际树深度回写，前端据此显示"二级大纲"
--
-- 三列都允许为空或有默认值：V32 已写入的历史行读到 NULL 时按旧行为（三级、无学历标签）解释。
-- ---------------------------------------------------------------------------------------------

ALTER TABLE thesis_documents
    ADD COLUMN education_level VARCHAR(32) NULL AFTER tier,
    ADD COLUMN paper_type VARCHAR(32) NULL AFTER education_level,
    ADD COLUMN outline_depth INT NOT NULL DEFAULT 3 AFTER paper_type;

-- 历史行的大纲都是三级结构，显式标注，免得保存时被误判成二级
UPDATE thesis_documents SET outline_depth = 3 WHERE outline_depth IS NULL OR outline_depth = 0;
