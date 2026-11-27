-- V27: 电商工具目录（commerce_tools）
--
-- 参考站的做法是把「电商工具」放在后端按分类下发，前端只负责渲染。
-- 这里用独立表而不是塞进 agents：agents 承担的是对话角色，
-- 54 个工具混进去会污染智能体目录，且需要改动 AgentService 的查询条件。
--
-- 15 个分类 / 54 个工具，
-- 由 AIneedNEW/gen-commerce-sql.mjs 从 data.ts 生成，勿手改。

CREATE TABLE IF NOT EXISTS commerce_tools (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    tool_key VARCHAR(128) NOT NULL,
    category VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NULL,
    prompt LONGTEXT NULL,
    form VARCHAR(32) NOT NULL DEFAULT 'replace',
    glyph VARCHAR(8) NULL,
    accent VARCHAR(16) NULL,
    upload_roles VARCHAR(2048) NULL,
    retouch_modes VARCHAR(512) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commerce_tools_site_key (site_id, tool_key),
    KEY idx_commerce_tools_catalog (site_id, status, sort_order),
    CONSTRAINT fk_commerce_tools_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 商品替换（8）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'display-stand-replace', '商品替换', '展示台座替换大师', '替换展示台座与商品陈列环境。',
    '替换商品展示台座，并保持商品材质和品牌信息不变。',
    'background', '台', '#f07a38',
    NULL, NULL, 10, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'scene-prop-replace', '商品替换', '场景道具替换器', '替换场景道具并匹配真实透视关系。',
    '替换商品场景中的道具，并匹配原有透视、材质和光线。',
    'background', '道', '#4e9b78',
    NULL, NULL, 20, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'season-atmosphere', '商品替换', '季节氛围替换大师', '根据季节氛围重建商品环境。',
    '为商品替换季节氛围背景，并保持商品色彩和细节准确。',
    'background', '季', '#bd8a36',
    NULL, NULL, 30, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'scene-migration', '商品替换', '商品场景迁移大师', '迁移商品到新的商业场景。',
    '将商品迁移到新的商业场景，保持原商品形态和比例。',
    'background', '迁', '#2b96ab',
    NULL, NULL, 40, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'hand-model-replace', '商品替换', '手持模特替换专家', '生成自然的手持商品展示效果。',
    '生成自然手持商品的展示画面，保持手部结构和商品外观准确。',
    'try-on', '手', '#d95d81',
    NULL, NULL, 50, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'package-replace', '商品替换', '产品包装替换器', '替换商品包装并保留品牌标识。',
    '替换商品包装设计，保持标签、结构和使用场景自然。',
    'replace', '包', '#ee8b32',
    NULL, NULL, 60, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'component-replace', '商品替换', '产品部件替换工具', '替换指定商品部件和配件。',
    '替换商品指定部件，保持连接关系、透视和材质一致。',
    'replace', '件', '#c8524e',
    NULL, NULL, 70, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'material-replace', '商品替换', '商品材质替换器', '替换商品材质并保留外形结构。',
    '替换商品表面材质，保留原始轮廓、结构和文字信息。',
    'replace', '材', '#8a7657',
    NULL, NULL, 80, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 商品换背景（9）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'one-click-background', '商品换背景', '一键商品换背景', '一键抠图并替换新的商品背景。',
    '一键识别商品并替换背景，保持边缘和阴影自然。',
    'background', '换', '#2e9ed1',
    NULL, NULL, 90, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'light-perspective', '商品换背景', '光影透视校正大师', '校正背景光影与透视关系。',
    '校正商品背景的光影、透视和色温，使画面真实自然。',
    'background', '光', '#518fd1',
    NULL, NULL, 100, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'artistic-background', '商品换背景', '艺术创意背景画板', '生成具有艺术感的背景画面。',
    '为商品生成具有品牌质感的艺术背景。',
    'background', '艺', '#7664d9',
    NULL, NULL, 110, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'batch-background', '商品换背景', '批量背景统一处理', '统一处理多张商品背景。',
    '批量统一商品背景风格、尺寸与光线。',
    'background', '批', '#43a073',
    NULL, NULL, 120, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'home-background', '商品换背景', '家居环境智能融合', '生成家居生活场景背景。',
    '生成真实的家居环境并融合商品。',
    'background', '家', '#b78345',
    NULL, NULL, 130, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'tech-background', '商品换背景', '数码科技背景设计', '生成数码科技质感背景。',
    '生成具有科技感的商品背景，保持商品主体清晰。',
    'background', '科', '#3d97bc',
    NULL, NULL, 140, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'food-background', '商品换背景', '美食场景背景搭配', '生成美食氛围和陈列背景。',
    '为食品商品生成自然诱人的陈列背景。',
    'background', '食', '#d57e4f',
    NULL, NULL, 150, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'beauty-background', '商品换背景', '美妆质感背景大师', '生成美妆产品的高级质感背景。',
    '生成适合美妆产品的高级商业背景。',
    'background', '美', '#d75f8d',
    NULL, NULL, 160, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'fashion-background', '商品换背景', '服饰风格背景定制', '生成服饰风格化的背景环境。',
    '为服饰商品生成风格统一的展示背景。',
    'background', '衣', '#8755b8',
    NULL, NULL, 170, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 模特试衣（1）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'virtual-model', '模特试衣', '虚拟模特试穿', '生成模特试穿和佩戴效果。',
    '生成自然模特试穿效果，保持商品版型、颜色和材质准确。',
    'try-on', '模', '#7287d8',
    NULL, NULL, 180, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- AI换模特（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'ai-model-swap', 'AI换模特', 'AI换模特', '保留商品和服装，替换画面中的模特形象。',
    '替换画面模特，同时保持商品、服装、姿势和镜头构图一致。',
    'try-on', '模', '#6f62e8',
    NULL, NULL, 190, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'model-face-swap', 'AI换模特', '模特脸型替换', '仅替换人物面部，并保持身材、服装和场景不变。',
    '替换模特面部特征，保持发型、肤色过渡、服装和背景自然一致。',
    'try-on', '脸', '#d66e8f',
    NULL, NULL, 200, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'model-pose-transfer', 'AI换模特', '模特姿态迁移', '迁移目标姿势，同时保留商品与模特身份特征。',
    '将原模特迁移到目标姿态，保持人物身份、服装商品和场景真实。',
    'try-on', '姿', '#4e8fd8',
    NULL, NULL, 210, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 商品抠图（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'image-cutout', '商品抠图', '商品抠图', '一键去除背景，保留商品主体。',
    '精准识别商品边缘并去除背景。',
    'retouch', '抠', '#397be5',
    NULL, NULL, 220, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'style-transfer', '商品抠图', '风格转换', '将商品图转换为不同艺术风格。',
    '转换商品图片艺术风格，同时保持商品主体一致。',
    'retouch', '风', '#9a57d7',
    NULL, NULL, 230, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'image-upscale', '商品抠图', '图片放大', 'AI 无损放大，提升图片清晰度。',
    '无损放大商品图片并提升细节清晰度。',
    'retouch', '放', '#23a56e',
    NULL, NULL, 240, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 鞋子上脚（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'shoe-on-foot', '鞋子上脚', '鞋子上脚', '将鞋款自然穿到模特脚上，匹配腿部比例和光线。',
    '将鞋子自然穿到模特脚上，保持鞋型、材质、透视和腿部结构准确。',
    'try-on', '鞋', '#d68145',
    NULL, NULL, 250, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'shoe-walking-showcase', '鞋子上脚', '鞋履行走展示', '生成适合鞋类详情页的侧面行走展示图。',
    '生成鞋履自然行走展示图，保持鞋底结构、鞋面材质和品牌细节准确。',
    'retouch', '行', '#479478',
    NULL, '["侧面行走展示","动态行走展示","鞋底结构展示","棚拍行走展示"]', 260, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'shoe-detail-retouch', '鞋子上脚', '鞋款细节精修', '强化鞋面纹理、缝线和材质细节。',
    '精修鞋款纹理、缝线、材质反光和轮廓，保持颜色与版型不变。',
    'retouch', '细', '#b98c3d',
    NULL, '["鞋面细节精修","鞋底结构增强","鞋款质感强化","鞋类高清放大"]', 270, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 一键穿搭（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'one-click-outfit', '一键穿搭', '一键全套穿搭', '根据一套服装自动生成完整模特上身穿搭。',
    '自动组合整套服饰并生成自然模特穿搭，保持每件商品的版型和颜色。',
    'try-on', '穿', '#734fd6',
    NULL, NULL, 280, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'outfit-flatlay', '一键穿搭', '服装平铺搭配', '将多件服装组合成简洁的商品平铺图。',
    '将服饰组合成电商平铺搭配图，统一比例、光线和背景风格。',
    'retouch', '铺', '#338da3',
    NULL, '["上下装平铺搭配","叠穿层次平铺","多品类组合平铺","杂志式留白平铺"]', 290, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'outfit-color-change', '一键穿搭', '穿搭换色', '快速调整整套服装或单品的颜色方案。',
    '调整服装颜色和配色方案，保留面料纹理、褶皱和原始版型。',
    'retouch', '色', '#d75a69',
    NULL, '["整套换色","上装换色","下装换色","配饰换色"]', 300, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 自定义试衣（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'custom-fit', '自定义试衣', '自定义合身试衣', '按人物尺寸调整服装比例，生成自然合身效果。',
    '根据人物比例调整服装合身度，保持面料、图案和背景真实。',
    'try-on', '合', '#7056cc',
    NULL, NULL, 310, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'garment-size-adjust', '自定义试衣', '服装尺码调整', '调整服装尺码感，同时保留款式和设计细节。',
    '调整服装尺码和宽松程度，保持款式结构、面料和图案细节一致。',
    'retouch', '尺', '#3e9f6a',
    NULL, '["更修身","更宽松","调整肩宽","调整衣长"]', 320, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'garment-detail-wear', '自定义试衣', '穿搭细节生成', '生成服装在真实模特身上的穿搭细节图。',
    '生成真实自然的服装穿搭细节图，突出面料、版型和搭配层次。',
    'try-on', '搭', '#3b7ad1',
    NULL, NULL, 330, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 穿戴图生成（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'jewelry-wear', '穿戴图生成', '首饰佩戴图', '将首饰自然佩戴到指定模特身上，匹配肤色与光线。',
    '将首饰自然佩戴到模特身上，保持金属质感、宝石颜色和人物结构准确。',
    'try-on', '饰', '#d7a132',
    NULL, NULL, 340, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'watch-wear', '穿戴图生成', '腕表佩戴图', '生成腕表、手链等手部佩戴展示图。',
    '将腕表或手链自然佩戴到手腕，保持表盘、材质和手部结构准确。',
    'try-on', '表', '#4f78b9',
    NULL, NULL, 350, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'bag-wear', '穿戴图生成', '箱包佩戴图', '生成箱包上肩、手提或斜挎的真实佩戴效果。',
    '生成箱包真实佩戴图，保持包型、肩带结构、人物姿态和环境自然。',
    'try-on', '包', '#b55e8e',
    NULL, NULL, 360, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 小红书风格（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'xhs-cover', '小红书风格', '小红书封面生成', '生成高点击感的小红书风格商品封面。',
    '生成小红书风格商品封面，突出主体、文字留白和社交平台审美。',
    'retouch', '红', '#ef4f7a',
    NULL, '["种草封面","测评封面","好物分享封面","新品推荐封面"]', 370, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'xhs-lifestyle', '小红书风格', '小红书生活场景', '把商品融入生活方式场景，适合种草内容。',
    '生成自然生活方式场景，突出商品使用氛围并保留真实质感。',
    'background', '生', '#d78070',
    NULL, NULL, 380, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'xhs-product-note', '小红书风格', '小红书种草笔记图', '生成带评价感和使用感受的商品图文素材。',
    '生成适合小红书种草笔记的商品场景图，保持画面真实、自然、有分享感。',
    'background', '记', '#aa65cf',
    NULL, NULL, 390, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 产品精修（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'product-retouch', '产品精修', '商品精修', '统一修复商品图的光线、颜色、材质和清晰度。',
    '精修商品图，统一光线、颜色、材质和清晰度，保持品牌信息准确。',
    'retouch', '精', '#4f7fd5',
    NULL, '["全图精修","光影统一","材质强化","瑕疵修复"]', 400, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'product-color-correct', '产品精修', '商品色彩校正', '修正商品色差、白平衡和整体色彩风格。',
    '校正商品颜色、白平衡和肤色环境，避免改变商品真实颜色。',
    'retouch', '彩', '#be6d44',
    NULL, '["白平衡校正","去除偏色","统一色调","提升色彩层次"]', 410, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'product-detail-enhance', '产品精修', '商品细节增强', '增强商品细节、边缘和材质清晰度。',
    '增强商品细节、边缘和材质清晰度，避免过度锐化和结构变形。',
    'retouch', '细', '#3e9c8f',
    NULL, '["细节增强","边缘锐化","材质清晰度","轻微降噪"]', 420, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 商品主图优化（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'platform-main-image', '商品主图优化', '平台主图优化', '一键生成符合电商平台规范的商品主图。',
    '优化商品主图构图、主体比例、留白和清晰度，适配主流电商平台。',
    'retouch', '主', '#3678d9',
    NULL, '["淘宝主图","京东主图","拼多多主图","通用平台主图"]', 430, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'white-background-main', '商品主图优化', '白底主图生成', '生成干净白底主图，并保留自然边缘和阴影。',
    '生成干净白底商品主图，保留自然边缘、轻微阴影和真实商品颜色。',
    'retouch', '白', '#36a26f',
    NULL, '["纯白底主图","浅灰底主图","透明边缘白底","自然投影白底"]', 440, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'main-image-layout', '商品主图优化', '主图版式优化', '调整主图主体位置和留白，方便后续添加卖点文字。',
    '优化商品主图版式，调整主体位置和留白，为标题卖点预留空间。',
    'retouch', '排', '#8271d4',
    NULL, '["居中构图","左右留白","上下留白","卖点区留白"]', 450, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 场景合成（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'scene-composition', '场景合成', '商品场景合成', '将多个商品素材合成为真实统一的商业场景。',
    '将商品与场景素材自然合成，统一透视、比例、颜色、光线和阴影。',
    'background', '合', '#5b77d5',
    NULL, NULL, 460, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'festival-scene', '场景合成', '节日营销场景', '生成节庆、促销和节点主题的商品场景。',
    '生成具有节日营销氛围的商品场景，保持商品主体清晰且品牌信息准确。',
    'background', '节', '#d55460',
    NULL, NULL, 470, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'lifestyle-scene', '场景合成', '生活方式场景', '生成真实自然的家居、户外或生活方式场景。',
    '生成真实自然的生活方式场景，并让商品融入环境光线和空间关系。',
    'background', '生', '#3d9c84',
    NULL, NULL, 480, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 服装搭配（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'outfit-matching', '服装搭配', 'AI服装搭配', '根据商品风格自动推荐并生成完整穿搭。',
    '根据商品风格生成完整穿搭，保持所有服饰商品的比例和颜色准确。',
    'try-on', '搭', '#8a62cf',
    NULL, NULL, 490, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'outfit-color-matching', '服装搭配', '穿搭配色设计', '提取商品主色并生成协调的上下装配色。',
    '根据商品主色生成协调的穿搭配色，保持服装款式和面料真实。',
    'try-on', '配', '#d17e43',
    NULL, NULL, 500, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'accessory-matching', '服装搭配', '鞋包配饰搭配', '为已有穿搭自动匹配合适的鞋包和配饰。',
    '为现有穿搭搭配鞋包和配饰，统一风格、颜色和场景氛围。',
    'try-on', '品', '#438fca',
    NULL, NULL, 510, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

-- 其他（3）
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'batch-commerce', '其他', '批量商品图处理', '批量统一多张商品图的背景、尺寸、光线和风格。',
    '批量统一商品图背景、尺寸、光线和颜色风格，保持每件商品细节准确。',
    'retouch', '批', '#69788c',
    NULL, '["统一背景","统一尺寸","统一光线","全流程批量"]', 520, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'poster-layout', '其他', '商业海报构图', '生成适合活动页和社媒投放的商品海报构图。',
    '生成商业海报式商品构图，保留标题留白并统一视觉重点和营销氛围。',
    'background', '报', '#a06bcf',
    NULL, NULL, 530, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, sort_order, status)
SELECT s.id,
    'commerce-cleanup', '其他', '商品图清理', '清理商品图中的水印、杂物和干扰元素。',
    '清理商品图杂物、水印和干扰元素，保持主体结构和背景自然。',
    'retouch', '清', '#547c9d',
    NULL, '["去除水印","清理杂物","去除瑕疵","净化背景"]', 540, 'ACTIVE'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    upload_roles = VALUES(upload_roles),
    retouch_modes = VALUES(retouch_modes),
    sort_order = VALUES(sort_order),
    status = VALUES(status);
