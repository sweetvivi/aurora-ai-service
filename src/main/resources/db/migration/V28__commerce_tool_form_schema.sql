-- V28: 电商工具的「表单 schema」
--
-- 背景：参考站里每个工具的表单都不一样——成套类型是多选胶囊，台座类型是下拉，
-- 替换说明是文本域。原来我们只有 6 种固定形态（form 字段），所有工具共用一套控件，
-- 做不出这种差异。
--
-- 做法：每个工具存一份 form_schema（JSON 字符串），前端按它渲染，新增工具不用改代码。
-- 用 VARCHAR 而不是 JSON 类型：作者在 UserIdentityEntity 里写明 Hibernate 的
-- ddl-auto=validate 在 MySQL 与 H2 之间无法可靠校验 JSON 列，全仓没有 JSON 列映射先例。
--
-- 本轮只配三个工具（参考站截图里的那三个），其余 51 个保持 form_schema 为空，
-- 前端会退回到原来按 form 推导上传槽的渲染，不会坏。
--
-- glyph 由「单字」升级成「图标类名」：remixicon 已在 main.ts 全局引入，
-- 存 ri-xxx-line 就能直接渲染，所以列宽从 8 放到 32。

ALTER TABLE commerce_tools ADD COLUMN form_schema VARCHAR(4000) NULL AFTER retouch_modes;

ALTER TABLE commerce_tools MODIFY COLUMN glyph VARCHAR(32) NULL;

-- 1/3 展示台座替换大师：三个下拉 + 单张商品图
UPDATE commerce_tools SET
    glyph = 'ri-store-3-line',
    form_schema = '{"uploads":[{"key":"product","label":"上传商品图片","required":true,"hint":"支持 jpg/png/webp，单张不超过 10MB"}],"fields":[{"key":"standType","type":"select","label":"台座类型","required":true,"placeholder":"请选择台座类型","options":["圆形展台","方形展台","阶梯台座","悬浮台座","亚克力台座"]},{"key":"standShape","type":"select","label":"台座形状","required":true,"placeholder":"请选择台座形状","options":["圆形","方形","椭圆","多边形"]},{"key":"resolution","type":"select","label":"图片分辨率","required":false,"placeholder":"请选择图片分辨率（可选）","options":["1024x1024","1536x1024","1024x1536"]}],"guide":{"intro":"识别并替换背景图片中的商品、保持原商品细节和真实度，适配多种摄影风格，适用于电商产品展示和营销素材制作。","steps":["上传包含原商品的图片","上传要替换的商品图片","选择替换模式与融合度","点击开始处理，等待AI完成修改"],"tips":["确保原图和替换商品的底色相似","建议使用高分辨率图片以获得更好效果","光线条件相近的图片替换效果更佳"]},"credits":{"perImage":2}}'
WHERE tool_key = 'display-stand-replace';

-- 2/3 组合套装替换器：10 个成套类型（多选）+ 两张图 + 说明/规格
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, form_schema, sort_order, status)
SELECT s.id,
       'bundle-set-replace', '商品替换', '组合套装替换器',
       '整套商品替换，保持套装层次与光源一致。',
       '把用户上传的商品替换进原始套装图片中，保持套装的层次关系、透视与光源方向一致，不改变包装上的品牌信息。',
       'replace', 'ri-gift-line', '#7c5cff',
       NULL, NULL,
       '{"uploads":[{"key":"product","label":"上传原始套装图片","required":true,"hint":"包含完整套装的原始图片"},{"key":"replacement","label":"上传替换的商品图","required":true,"hint":"要换进套装的商品，建议白底图"}],"fields":[{"key":"setType","type":"chips","label":"成套类型","required":true,"multiple":true,"options":["美妆个护套装","香水礼盒","数码配件组合","文具礼盒","母婴用品套装","家居装饰套装","运动装备组合","节日礼盒","办公用品套装","宠物用品套装"]},{"key":"note","type":"textarea","label":"替换说明","required":false,"placeholder":"例如：把第二张图的商品换到第一张图左侧位置"},{"key":"spec","type":"text","label":"规格要求","required":false,"placeholder":"例如：保持原图尺寸，输出 4:3"}],"guide":{"intro":"识别原图片中的整套商品，保留套装的完整性和真实度，支持多种商品类型，适用于电商产品展示和营销素材制作。","steps":["上传原始套装图片","上传替换的商品图片","选择成套类型与融合度","点击开始处理，等待AI完成修改"],"tips":["确保原图和替换商品的底色相似","建议使用高分辨率图片以获得更好效果","光线条件相近的图片替换效果更佳"]},"credits":{"perImage":2}}',
       0, 'ACTIVE'
FROM app_sites s
ON DUPLICATE KEY UPDATE
    name = VALUES(name), description = VALUES(description), prompt = VALUES(prompt),
    form = VALUES(form), glyph = VALUES(glyph), accent = VALUES(accent),
    form_schema = VALUES(form_schema), sort_order = VALUES(sort_order), status = VALUES(status);

-- 3/3 服饰图案替换设计师：12 个图案类目（多选）+ 应用方式/融合度/分辨率下拉
INSERT INTO commerce_tools
    (site_id, tool_key, category, name, description, prompt, form, glyph, accent,
     upload_roles, retouch_modes, form_schema, sort_order, status)
SELECT s.id,
       'apparel-pattern-replace', '商品替换', '服饰图案替换设计师',
       '把图案替换到服饰上，保持版型与褶皱阴影。',
       '把用户上传的图案替换到服饰图片上，贴合面料褶皱与光影，保持服饰版型、颜色与真实度不变。',
       'pattern', 'ri-t-shirt-line', '#d95d81',
       NULL, NULL,
       '{"uploads":[{"key":"product","label":"上传基础服饰图片","required":true,"hint":"要替换图案的服饰图"},{"key":"pattern","label":"上传替换图案图片","required":true,"hint":"图案素材，建议平铺方形图"}],"fields":[{"key":"applyMode","type":"select","label":"图案应用方式","required":false,"placeholder":"请选择图案应用方式","options":["满印平铺","局部点缀","胸前主图","满身循环"]},{"key":"apparelType","type":"chips","label":"图案类目","required":false,"multiple":true,"options":["T恤","连衣裙","衬衫","卫衣","外套","运动服","裤子","裙子","帽子","休闲服","家居服","配饰"]},{"key":"blend","type":"select","label":"图案融合度","required":true,"placeholder":"请选择图案融合度","options":["低（保留图案原貌）","中（贴合面料纹理）","高（融合褶皱与光影）"]},{"key":"note","type":"textarea","label":"额外要求","required":false,"placeholder":"例如：图案不要覆盖领口和袖口"},{"key":"resolution","type":"select","label":"图片分辨率","required":false,"placeholder":"请选择图片分辨率（可选）","options":["1024x1024","1536x1024","1024x1536"]}],"guide":{"intro":"智能识别并替换图片中的服饰图案，保持服饰的版型、褶皱与光影真实度，支持多种服饰品类，适用于电商产品展示和营销素材制作。","steps":["上传基础服饰图片","上传替换图案图片","选择图案应用方式与融合度","点击开始处理，等待AI完成修改"],"tips":["确保原图和替换图案的底色相似","建议使用高分辨率图片以获得更好效果","图案与面料纹理方向一致时效果更佳"]},"credits":{"perImage":2}}',
       3, 'ACTIVE'
FROM app_sites s
ON DUPLICATE KEY UPDATE
    name = VALUES(name), description = VALUES(description), prompt = VALUES(prompt),
    form = VALUES(form), glyph = VALUES(glyph), accent = VALUES(accent),
    form_schema = VALUES(form_schema), sort_order = VALUES(sort_order), status = VALUES(status);
