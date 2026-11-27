-- V82: 绘画工具库补齐（第二批）
--
-- 上一批（V81）只做了 4 个分类 × 4 个工具，用来验证「点工具 → 出表单 → 出结果」这条链路。
-- 链路通了之后，把首页工具矩阵里剩下一批「点进去只能填一句提示词」的入口收进工具库：
--
--   调色与光效（3）：色彩调整 / 滤镜特效 / 光影氛围
--   创意与合成（3）：场景变换 / 图片融合 / 创意海报
--
-- 这两个分类是按矩阵卡片上写的说明归的（「调色 · 增强 · 编辑」「换景 · 增强氛围」
-- 「海报 · 插画」「多图 · 风格合成」），保证用户点哪个卡片都能落到一个具体的分类。
--
-- 「尺寸调整」不在这里：它是纯前端居中裁剪（`views/painting/cropImage.ts`），
-- 不花积分也不走模型，做成表单反而多绕一圈。
--
-- form_schema 与 V81 同结构：uploads（顺序即「第几张图」）/ fields / guide / credits。
-- VARCHAR(4000) 是硬上限，每个 schema 都贴着这个预算写，别再往里塞长文案。

-- 调色与光效（3）
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-color-tune', '调色与光效', '色彩调整', '亮度 · 对比度 · 色调。',
    '调整这张图片的整体色彩：按用户指定的方向调整亮度、对比度、饱和度、色温与色调。只做整体调色，画面内容、构图、物体形状与文字必须保持不变；保留原有的细节与质感，避免过曝、死黑、色彩断层和溢出。',
    'retouch', 'ri-contrast-drop-line', '#0ea5e9', 210, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传要调色的图片"}],"fields":[{"key":"tone","type":"select","label":"调色方向","required":true,"options":["原图校正（去灰、还原真实色彩）","清透明亮","浓郁饱满","低饱和高级灰","暖调","冷调"]},{"key":"strength","type":"chips","label":"调整幅度","required":true,"multiple":false,"options":["轻微","适中","明显"]}],"credits":{"note":"每次生成 1 张"}}'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    scene = VALUES(scene),
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    form_schema = VALUES(form_schema);
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-filter-look', '调色与光效', '滤镜特效', '胶片 · 柔光 · 复古。',
    '给这张图加上用户选择的滤镜效果。滤镜只影响色彩与光感（色调、颗粒、暗角、柔光），不得改变画面内容、五官、物体外形与文字；强度按用户选择控制，避免糊掉细节，也不要把肤色滤成不自然的颜色。',
    'retouch', 'ri-filter-3-line', '#8b5cf6', 220, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传要加滤镜的图片"}],"fields":[{"key":"look","type":"select","label":"滤镜风格","required":true,"options":["胶片颗粒","日系柔光","赛博霓虹","复古拍立得","黑白胶片","油画质感"]},{"key":"strength","type":"chips","label":"强度","required":true,"multiple":false,"options":["轻","中","重"]}],"credits":{"note":"每次生成 1 张"}}'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    scene = VALUES(scene),
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    form_schema = VALUES(form_schema);
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-light-mood', '调色与光效', '光影氛围', '补光 · 逆光 · 氛围。',
    '重做这张图的光影与氛围：按用户选择调整主光方向、明暗层次与环境色，可加入逆光、体积光、黄金时刻等效果。光源方向必须与原图场景逻辑一致，人物与物体的投影要随之更新；不改变人物身份、物体外形与画面内容。',
    'retouch', 'ri-sun-line', '#f59e0b', 230, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传要调整光影的图片"}],"fields":[{"key":"mood","type":"select","label":"氛围","required":true,"options":["自然柔光","夕阳黄金时刻","清晨薄雾","夜晚霓虹","棚拍硬光"]},{"key":"extra","type":"text","label":"补充要求","placeholder":"如「左后方加一束窗光」","required":false}],"credits":{"note":"每次生成 1 张"}}'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    scene = VALUES(scene),
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    form_schema = VALUES(form_schema);

-- 创意与合成（3）
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-scene-swap', '创意与合成', '场景变换', '换场景 · 季节 · 天气。',
    '把图片中的场景替换为用户描述的环境（地点、季节、天气、时间），主体保持原样：人物身份、姿势、服装、物体外形与相对位置不变。主体在新场景中的光影方向、色温、投影与透视需与背景一致，边缘衔接自然，不能有抠图痕迹。',
    'retouch', 'ri-landscape-line', '#10b981', 240, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传人物或商品图"}],"fields":[{"key":"scene","type":"text","label":"目标场景","required":true,"placeholder":"如「海边落日沙滩」「冬季雪后的街道」"},{"key":"weather","type":"chips","label":"天气/时间","required":false,"multiple":false,"options":["晴天","阴天","雨天","雪天","黄昏","夜景"]}],"credits":{"note":"每次生成 1 张"}}'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    scene = VALUES(scene),
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    form_schema = VALUES(form_schema);
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-image-merge', '创意与合成', '图片融合', '两张图自然合成一张。',
    '把两张图片自然融合成一张：第 1 张作为场景底图，第 2 张的主体按用户要求放入其中。主体的外观、颜色与识别特征必须保持原样；融合处的光影、色温、清晰度与底图一致，边缘不能有白边、重影或明显拼接感。',
    'retouch', 'ri-stack-line', '#6366f1', 250, 'ACTIVE',
    '{"uploads":[{"key":"base","label":"场景底图（第 1 张）","hint":"最终画面的背景与环境"},{"key":"subject","label":"要放入的主体（第 2 张）","hint":"人物或商品，背景干净效果更好"}],"fields":[{"key":"position","type":"select","label":"放置位置","required":true,"options":["画面中央","左侧","右侧","前景特写"]},{"key":"scale","type":"chips","label":"主体大小","required":false,"multiple":false,"options":["小","中","大"]}],"credits":{"note":"每次生成 1 张"}}'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    scene = VALUES(scene),
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    form_schema = VALUES(form_schema);
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-poster', '创意与合成', '创意海报', '海报 · 插画 · 版式。',
    '以这张图为素材做一张海报或插画：按用户要求的主题与用途重新构图，可添加标题文字、留白与装饰元素。画面上的文字必须严格使用用户给出的字样，不自行编造或改写；主体要保持可识别，风格统一，输出完整的一张图。',
    'retouch', 'ri-lightbulb-line', '#ec4899', 260, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传素材图"}],"fields":[{"key":"theme","type":"text","label":"海报主题/用途","required":true,"placeholder":"如「咖啡店秋季新品」「乐队演出」"},{"key":"copy","type":"text","label":"画面文字","required":false,"placeholder":"主标题与副标题，逐字使用"},{"key":"style","type":"select","label":"版式风格","required":true,"options":["极简留白","杂志大片","复古印刷","手绘插画","赛博科技"]}],"credits":{"note":"每次生成 1 张"}}'
FROM app_sites s WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE
    scene = VALUES(scene),
    category = VALUES(category),
    name = VALUES(name),
    description = VALUES(description),
    prompt = VALUES(prompt),
    form = VALUES(form),
    glyph = VALUES(glyph),
    accent = VALUES(accent),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    form_schema = VALUES(form_schema);
