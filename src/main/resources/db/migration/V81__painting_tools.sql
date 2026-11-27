-- V80: 绘画工具库（复用 commerce_tools，靠 scene 区分场景）
--
-- 背景：参考站把「AI 绘画」下的工具按钮做成了工具库——点分类进列表、点工具出表单，
-- 每个工具一套字段。我们已有的电商工具表（V27/V28）就是这个模型，没必要再建一张
-- 「长得一样、只是内容不同」的表：
--
--   scene = 'commerce'  电商工具（56 个，/e-commerce 与超级智能体引用）
--   scene = 'painting'  绘画工具（/painting 的图片创作工具库）
--
-- 为什么不复用 category 区分：category 已经是「分类」（商品替换 / 抠图去背 …），
-- 是分类下的二级概念，混用会让目录分组和服务端过滤都没法写。
--
-- 本轮只做 4 个分类 × 4 个工具，先把「点工具 → 出表单 → 出结果」这条链路跑通，
-- 剩下的按实际使用反馈再加。命名用功能名（「智能抠图」而不是「抠图专家」）：
-- 拟人化命名是否合适还没定，先不把产品语气写进数据。
--
-- form_schema 与 V28 同一结构：uploads（顺序即「第几张图」）/ fields / credits。

ALTER TABLE commerce_tools ADD COLUMN scene VARCHAR(32) NOT NULL DEFAULT 'commerce' AFTER site_id;

-- 目录查询与智能体工具清单都按 (site_id, scene, status) 过滤
CREATE INDEX idx_commerce_tools_scene ON commerce_tools (site_id, scene, status, sort_order);

-- 抠图去背（4）
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-cutout-subject', '抠图去背', '智能抠图', '一键去掉背景，只保留主体。',
    '去掉图片的背景，只保留主体（人物、商品或指定物体）。主体边缘保持干净自然，不出现白边或镂空；主体本身的颜色、纹理、logo 文字必须原样保留。输出背景按用户要求处理，未指定时用纯白。',
    'retouch', 'ri-scissors-line', '#0ea5e9', 10, 'ACTIVE',
    '{"uploads":[{"key":"subject","label":"上传要抠图的图片","hint":"主体清晰、与背景有明显区分时效果最好"}],"fields":[{"key":"edge","type":"select","label":"边缘精细度","required":true,"options":["标准","精细（发丝、绒毛）","硬边物体"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-cutout-portrait', '抠图去背', '人像抠图', '发丝级边缘，适合证件照与合成。',
    '抠出图中的人物，重点处理头发、发丝、半透明衣物等细节边缘，保持发丝自然飘逸、不糊成一团。人物面部、肤色、妆容保持原样。输出背景用纯白。',
    'retouch', 'ri-user-smile-line', '#f472b6', 20, 'ACTIVE',
    '{"uploads":[{"key":"subject","label":"上传含人物的图片","hint":"正脸或侧脸均可，多人请说明要保留谁"}],"fields":[{"key":"keep","type":"text","label":"要保留的人","placeholder":"多人合影时说明，如「左边穿白衣的女生」","required":false}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-cutout-product', '抠图去背', '商品抠图', '白底图与透明底，可直接上架。',
    '抠出图片中的商品主体，边缘干净利落，保留商品原有的材质光泽、反光与品牌标识。不要美化或改变商品外形。按用户选择的输出方式处理背景。',
    'retouch', 'ri-shopping-bag-3-line', '#f59e0b', 30, 'ACTIVE',
    '{"uploads":[{"key":"product","label":"上传商品图","hint":"建议主体占画面 60% 以上"}],"fields":[{"key":"background","type":"chips","label":"输出背景","required":true,"multiple":false,"options":["纯白","透明","浅灰"]},{"key":"shadow","type":"chips","label":"保留投影","required":false,"multiple":false,"options":["保留","去掉"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-bg-replace', '抠图去背', '更换背景', '抠出主体后换成指定场景。',
    '把第一张图里的主体自然地放到第二张图的背景中，统一透视关系、光源方向与色温，主体边缘与背景交接处过渡自然，不出现生硬的剪贴感。主体本身不变形、不改色。',
    'retouch', 'ri-landscape-line', '#22c55e', 40, 'ACTIVE',
    '{"uploads":[{"key":"subject","label":"上传主体图","hint":"要保留的人或物"},{"key":"background","label":"上传背景图","hint":"期望的新环境"}],"fields":[{"key":"blend","type":"chips","label":"融合程度","required":false,"multiple":false,"options":["真实自然","保留主体风格"]},{"key":"style","type":"text","label":"场景要求","placeholder":"如「换成咖啡店窗边，下午自然光」","required":false}],"credits":{"note":"每次生成 1 张"}}'
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

-- 修复增强（4）
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-photo-restore', '修复增强', '老照片修复', '补划痕、去霉斑、还原褪色。',
    '修复这张老照片：去除划痕、折痕、霉斑与噪点，修补缺失区域，还原自然的人物面部细节。保持原有构图、人物身份与年代感，不要把老照片修成现代高清照，也不要改变人物长相。可以轻微提升清晰度，但颗粒感与色调应保留老照片的质感。',
    'retouch', 'ri-time-line', '#a78bfa', 50, 'ACTIVE',
    '{"uploads":[{"key":"photo","label":"上传老照片","hint":"建议扫描或翻拍，缺陷越少效果越好"}],"fields":[{"key":"level","type":"select","label":"修复强度","required":true,"options":["轻度（保留颗粒）","标准","深度（尽量清晰）"]},{"key":"colorize","type":"chips","label":"上色","required":false,"multiple":false,"options":["保持黑白","轻度上色","完整上色"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-upscale', '修复增强', '画质增强', '放大不失真，补足细节。',
    '提升这张图片的清晰度与解析力：锐化边缘、重建纹理细节、减少压缩噪点与块状伪影，让画面在高分辨率下依然干净。保持原有构图、内容与色调不变，不要添加原本不存在的物体。',
    'retouch', 'ri-zoom-in-line', '#06b6d4', 60, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传要增强的图片"}],"fields":[{"key":"target","type":"chips","label":"目标清晰度","required":true,"multiple":false,"options":["2K","4K"]},{"key":"keep_grain","type":"chips","label":"胶片颗粒","required":false,"multiple":false,"options":["保留","去掉"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-denoise', '修复增强', '去噪去模糊', '压噪点、修轻微抖动模糊。',
    '去除这张图片的噪点与压缩伪影，修复轻微的对焦不准或抖动模糊，让主体重新清晰。保持原有构图、光影与色彩，不要过度锐化产生硬边或白边，也不要改变画面内容。',
    'retouch', 'ri-contrast-drop-line', '#64748b', 70, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传有噪点或模糊的图片"}],"fields":[{"key":"strength","type":"select","label":"处理强度","required":true,"options":["轻度","标准","强力"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-object-remove', '修复增强', '消除杂物', '抹掉指定物体并自动补背景。',
    '去掉图片中用户指定的物体或文字，并用周围的背景自然填补该区域，填补处的纹理、光影方向、色温要与周围一致，不留痕迹。其余部分保持完全不变。',
    'retouch', 'ri-eraser-line', '#ef4444', 80, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传要处理的图片"}],"fields":[{"key":"target","type":"text","label":"要去掉的内容","required":true,"placeholder":"如「右下角的水印」「桌上的易拉罐」"}],"credits":{"note":"每次生成 1 张"}}'
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

-- 风格转换（4）
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-style-anime', '风格转换', '动漫风', '日系二次元画风。',
    '把这张图片转成日系动漫风格：干净的描线、平涂上色、明确的高光与阴影分区，人物五官与发型按动漫比例适度概括，但保留原图人物的识别特征与姿势构图。整体色彩通透，避免脏色。',
    'retouch', 'ri-palette-line', '#ec4899', 90, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传原图"}],"fields":[{"key":"strength","type":"chips","label":"风格强度","required":true,"multiple":false,"options":["轻微","适中","强烈"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-style-oil', '风格转换', '油画风', '厚涂笔触与古典色调。',
    '把这张图片转成油画风格：可见的厚涂笔触、颜料堆叠的肌理、柔和的色彩过渡与古典光影。保持原图的主体、构图与人物特征可辨认，不要变成抽象画。',
    'retouch', 'ri-brush-line', '#b45309', 100, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传原图"}],"fields":[{"key":"school","type":"select","label":"画派倾向","required":true,"options":["古典写实","印象派","表现主义"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-style-3d', '风格转换', '3D 卡通', '皮克斯质感的三维角色。',
    '把这张图片转成 3D 卡通渲染风格：圆润的几何形体、柔和的全局光照、带高光的塑料或黏土材质。人物按皮克斯式比例做适度夸张（头身比、眼睛大小），但保留原图人物的辨识度、姿势与构图。',
    'retouch', 'ri-box-3-line', '#8b5cf6', 110, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传原图"}],"fields":[{"key":"material","type":"chips","label":"材质","required":false,"multiple":false,"options":["黏土","塑料","毛绒"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-style-film', '风格转换', '胶片质感', '颗粒、暗角与胶片色调。',
    '给这张照片加上胶片摄影的质感：细腻的颗粒、轻微暗角、柔和的对比曲线、偏青绿或暖黄的胶片色调，高光有轻微溢出。保持构图、人物与内容完全不变，只做影调与质感处理。',
    'retouch', 'ri-film-line', '#0f766e', 120, 'ACTIVE',
    '{"uploads":[{"key":"image","label":"上传原图"}],"fields":[{"key":"tone","type":"select","label":"胶片色调","required":true,"options":["日光暖黄","冷调青绿","黑白"]}],"credits":{"note":"每次生成 1 张"}}'
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

-- 人像美化（4）
INSERT INTO commerce_tools
    (site_id, scene, tool_key, category, name, description, prompt, form, glyph, accent,
     sort_order, status, form_schema)
SELECT s.id, 'painting', 'paint-portrait-retouch', '人像美化', '人像精修', '磨皮、匀肤、微调五官。',
    '对这张人像做自然精修：平滑肤质但保留皮肤纹理与毛孔，均匀肤色、减弱痘印与红血丝，柔化法令纹与眼袋，提亮眼神光。五官比例、脸型、人物身份必须保持不变，不要做成网红脸或过度磨皮的塑料感。',
    'retouch', 'ri-user-heart-line', '#f43f5e', 130, 'ACTIVE',
    '{"uploads":[{"key":"portrait","label":"上传人像照片","hint":"正脸、光线均匀效果最好"}],"fields":[{"key":"level","type":"select","label":"精修程度","required":true,"options":["自然（保留纹理）","标准","精致"]},{"key":"extra","type":"text","label":"额外要求","placeholder":"如「去掉眼镜反光」","required":false}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-id-photo', '人像美化', '证件照', '换底、正装、一寸二寸。',
    '把这张照片做成标准证件照：人物居中、双肩水平、头部占比符合证件照规范，换纯色背景，光线均匀无阴影，表情自然。保留人物五官与身份特征，如需正装则自然地换上衬衫或西服外套。',
    'retouch', 'ri-id-card-line', '#3b82f6', 140, 'ACTIVE',
    '{"uploads":[{"key":"portrait","label":"上传人像照片","hint":"正面免冠、光线均匀"}],"fields":[{"key":"bg","type":"chips","label":"背景底色","required":true,"multiple":false,"options":["白色","蓝色","红色","浅灰"]},{"key":"wear","type":"select","label":"着装","required":false,"options":["保持原样","白色衬衫","深色西服"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-outfit', '人像美化', '换装', '保留人物与姿势，替换服装。',
    '把第一张图人物的服装换成第二张图中的款式：服装的版型、颜色、图案与材质要与参考图一致，并自然地贴合人物原有姿势与身体比例，处理好领口、袖口、褶皱与光影。人物面部、发型、肤色与背景保持不变。',
    'retouch', 'ri-t-shirt-line', '#14b8a6', 150, 'ACTIVE',
    '{"uploads":[{"key":"portrait","label":"上传人物图","hint":"全身或半身，姿势自然"},{"key":"clothing","label":"上传服装图","hint":"平铺图或模特上身图均可"}],"fields":[{"key":"fit","type":"chips","label":"版型","required":false,"multiple":false,"options":["保持原版型","按人物身材调整"]}],"credits":{"note":"每次生成 1 张"}}'
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
SELECT s.id, 'painting', 'paint-makeup', '人像美化', '妆造', '上妆、换发型与造型。',
    '给这张人像做妆造：按用户要求的风格上妆（底妆、眼妆、唇色、腮红），妆感自然、贴合肤色与五官。如涉及发型或造型变化，需与脸型协调、发丝自然。人物身份、五官比例与脸型必须保持不变。',
    'retouch', 'ri-brush-4-line', '#d946ef', 160, 'ACTIVE',
    '{"uploads":[{"key":"portrait","label":"上传人像照片"}],"fields":[{"key":"style","type":"select","label":"妆容风格","required":true,"options":["日常淡妆","通勤妆","晚宴妆","国风妆"]},{"key":"hair","type":"text","label":"发型要求","placeholder":"如「换成低马尾」「染栗棕色」","required":false}],"credits":{"note":"每次生成 1 张"}}'
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
