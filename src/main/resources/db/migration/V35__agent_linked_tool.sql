-- ---------------------------------------------------------------------------------------------
-- V35: 智能体的「表单形态」：一个智能体可以引用一个电商工具，详情页就多出左侧「开始创作」面板
--
-- 背景：参考站（ai1foo）的智能体详情页里，绝大多数是空板（欢迎语 + 输入框），少数配了
-- 「界面定制 = 自定义界面」，左侧出现一组必填表单 + 「立即生成」。我们已经有 56 个电商工具的
-- form_schema（uploads / fields / guide / gallery / credits），形状与它几乎一致，所以不需要
-- 复制一份 schema 到智能体上 —— 存一个 tool_key 引用，运营改工具时智能体自动跟随。
--
-- 语义：
--   linked_tool_key IS NULL    → 纯对话形态（现状，什么都不变）
--   linked_tool_key = 'xxx'    → 详情页渲染该工具的表单面板；提交走 /commerce/tools/{key}/compose
-- ---------------------------------------------------------------------------------------------

ALTER TABLE agents
    ADD COLUMN linked_tool_key VARCHAR(128) NULL AFTER child_classify;

-- 一条示例：把「一键商品换背景」配成一个自定义界面的智能体。
-- 对应参考站里「封面爆破制造机」那种形态（一个智能体 = 一个工具）。
INSERT INTO agents (site_id,
                    category_id,
                    agent_key,
                    sn,
                    agent_type,
                    ui_type,
                    scope,
                    classify,
                    name,
                    description,
                    system_prompt,
                    chat_prologue,
                    linked_tool_key,
                    visibility,
                    publish_status,
                    status,
                    is_hot,
                    sort_order,
                    published_at)
SELECT (SELECT MIN(id) FROM app_sites),
       (SELECT id FROM agent_categories WHERE category_key = 'business-product'),
       'commerce-background-swap',
       'commerce-background-swap',
       'OFFICIAL',
       1,
       1,
       '业务与产品',
       '一键商品换背景',
       '上传商品图、选个背景类型，点一下直接出成品图。也能在下面直接说你的要求。',
       '你是商品图换背景助手。用户会用左侧表单上传商品图并选择背景类型，也可能直接在对话里补充要求。回答保持简短，重点是把图给出来。',
       '选好背景类型，点「立即生成」；有特殊要求可以直接在下面说。',
       'one-click-background',
       'PUBLIC',
       'PUBLISHED',
       'ACTIVE',
       0,
       10,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE sn = 'commerce-background-swap');
