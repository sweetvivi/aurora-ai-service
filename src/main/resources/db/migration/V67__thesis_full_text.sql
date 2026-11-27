-- ---------------------------------------------------------------------------------------------
-- 论文写作：把生成好的全文存回稿件
--
-- 背景：「保存稿件」原先只存大纲，全文生成完只存在于前端内存 —— 一关阅读器就没了，
-- 「我的稿件」里也只能看到一个订单号。这里把整份全文（章节 + 参考文献 + 字数）存成一列 JSON：
--
-- * 章节是有序的、只整体读取，没有按章查询的需求 —— 与 outline_json 同样的取舍；
-- * full_text_word_count 单独落一列，列表页不必解析 JSON 就能显示字数；
-- * full_text_generated_at 让前端能说清「这份正文是什么时候生成的」，大纲改动后也不会误认。
-- ---------------------------------------------------------------------------------------------

ALTER TABLE thesis_documents
    ADD COLUMN full_text_json LONGTEXT NULL AFTER outline_version,
    ADD COLUMN full_text_model VARCHAR(128) NULL AFTER full_text_json,
    ADD COLUMN full_text_word_count INT NOT NULL DEFAULT 0 AFTER full_text_model,
    ADD COLUMN full_text_generated_at DATETIME(6) NULL AFTER full_text_word_count;
