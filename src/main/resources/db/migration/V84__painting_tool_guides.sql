-- V84: 绘画工具的使用指南（schema.guide）
--
-- 每个工具的 schema 里加一段 guide：一句话简介 + 操作步骤 + 实用技巧。
-- 绘画工具面板在**还没有出结果**时把它显示在右栏；出了图就让位给结果。
--
-- 为什么放进 schema 而不是写在前端：这些内容每个工具都不一样，写死在组件里
-- 等于 22 份 if/else；而 schema 本来就在管理端可视化编辑（V82 之后有「使用指南」区块）。
--
-- 只补 guide、不重写整个 form_schema —— 运营可能已经改过 uploads/fields，
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 会把那些改动一起冲掉。
--
-- 幂等条件：`$.guide` 为空才写。已经有人写过就跳过，重放不会覆盖。
--
-- 文案约定：只写这个工具**独有**的要点。页面本身已经写着「先传图再点生成」，
-- 指南里再重复一遍就是占地方。

-- 抠图去背
UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '去掉背景只留主体，边缘不发白，主体的颜色、纹理和 logo 保持原样。',
        'steps', JSON_ARRAY('上传主体清晰的图片', '按主体类型选边缘精细度', '点生成，一般 30–60 秒出图'),
        'tips', JSON_ARRAY('主体与背景颜色差别越大，边缘越干净', '玻璃、纱这类半透明物体容易残留背景色，建议改用人像抠图'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-cutout-subject'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '发丝级边缘，适合证件照、合成与换背景。',
        'steps', JSON_ARRAY('上传含人物的照片', '多人合影时写清要保留谁', '点生成'),
        'tips', JSON_ARRAY('逆光、发丝松散的照片抠得最干净', '深色头发配深色背景容易糊成一片，换一张或先在修图页提亮'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-cutout-portrait'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '白底或透明底，边缘利落，可直接拿去上架。',
        'steps', JSON_ARRAY('上传商品图', '选输出背景（纯白 / 透明 / 浅灰）', '决定要不要保留投影', '点生成'),
        'tips', JSON_ARRAY('主体占画面 60% 以上最准', '玻璃、金属这类反光商品建议保留投影，否则会“浮”在背景上'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-cutout-product'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '先抠出主体，再换成一整片新背景，主体本身不动。',
        'steps', JSON_ARRAY('上传要换背景的图', '描述目标背景', '点生成'),
        'tips', JSON_ARRAY('把光线和时间一起说清（如「阴天下午的咖啡馆窗边」），比只说地点准', '只想换场景氛围、保留原图部分元素，用「创意与合成 · 场景变换」'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-bg-replace'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

-- 修复增强
UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '补划痕、去霉斑、还原褪色，尽量修回当年的样子。',
        'steps', JSON_ARRAY('上传扫描或翻拍的老照片', '点生成', '不满意就再跑一次，每次结果会略有不同'),
        'tips', JSON_ARRAY('先自己把照片摆正、裁掉边框，修复会更集中在画面内容上', '破损严重的地方模型会“补内容”，重要的人脸建议单独再修一次'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-photo-restore'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '放大并补足细节，适合小图、截图和老素材。',
        'steps', JSON_ARRAY('上传要放大的图片', '点生成', '下载后再用，不要拿预览图去印刷'),
        'tips', JSON_ARRAY('纯色或简洁背景的产品图效果最好', '原图已经看不出结构时，放大只会“糊得更清楚”，先修复再放大'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-upscale'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '压住噪点、修掉轻微抖动模糊，画面内容不变。',
        'steps', JSON_ARRAY('上传噪点多或有轻微模糊的图', '点生成'),
        'tips', JSON_ARRAY('夜景、高感光度拍的噪点最适合这个工具', '运动拖影修不回来，那属于另一类问题'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-denoise'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '抹掉指定的物体或文字，自动用周围背景补上。',
        'steps', JSON_ARRAY('上传图片', '写清要去掉什么（位置 + 是什么）', '点生成'),
        'tips', JSON_ARRAY('要去的物体越小、背景越规整，补得越自然', '超过画面三分之一的大面积，建议分成几次小范围处理'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-object-remove'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

-- 风格转换
UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '转成日系二次元画风，保留人物特征和构图。',
        'steps', JSON_ARRAY('上传照片或插画', '点生成', '不满意可以再跑一次，画风每次略有差异'),
        'tips', JSON_ARRAY('真人照片转二次元会把年龄感拉低，介意的话先用「人像美化 · 人像精修」定住五官', '背景越简单，线条越干净'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-style-anime'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '厚涂笔触加古典色调，画面像真的画出来的。',
        'steps', JSON_ARRAY('上传图片', '点生成'),
        'tips', JSON_ARRAY('风景、静物最出效果', '人脸的厚涂笔触容易让五官变形，肖像慎用'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-style-oil'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '皮克斯质感的立体角色，圆润、有光泽。',
        'steps', JSON_ARRAY('上传人物或宠物照片', '点生成'),
        'tips', JSON_ARRAY('正脸、光线柔和的照片转出来最像', '侧脸、手挡脸这类遮挡多的照片容易崩'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-style-3d'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '颗粒、暗角、低饱和的胶片色，只改色彩不动内容。',
        'steps', JSON_ARRAY('上传图片', '点生成'),
        'tips', JSON_ARRAY('原图本身通透时效果最好', '想让颜色更“旧”，可以先自己降一点饱和再进来'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-style-film'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

-- 人像美化
UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '磨皮、匀肤、微调五官，保持本人长相。',
        'steps', JSON_ARRAY('上传人像', '点生成', '对比前后再决定要不要再来一次'),
        'tips', JSON_ARRAY('皮肤状态差（痘印、油光）时改善最明显', '一次别修太狠，宁可跑两次'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-portrait-retouch'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '换底色、换正装，按一寸二寸出图，人脸不动。',
        'steps', JSON_ARRAY('上传正面免冠照', '说明用途（背景色、尺寸）', '点生成'),
        'tips', JSON_ARRAY('原图要正脸、无遮挡、光线均匀', '戴眼镜时镜片反光会被保留，需要先处理掉'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-id-photo'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '保留人物、姿势和脸，只把衣服换掉。',
        'steps', JSON_ARRAY('上传全身照', '描述目标服装', '点生成'),
        'tips', JSON_ARRAY('站姿、四肢清晰的图最好换', '描述带上颜色和材质（「米色针织开衫」）比只说款式准'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-outfit'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '上妆、换发型和造型，五官比例保持不变。',
        'steps', JSON_ARRAY('上传人像', '选妆容风格', '要换发型就补充说明长度和颜色', '点生成'),
        'tips', JSON_ARRAY('淡妆比浓妆稳，浓妆容易失真', '换发型一定写清长度和颜色，不然模型会自由发挥'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-makeup'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

-- 调色与光效
UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '只调整体色调，画面内容一个元素都不动。',
        'steps', JSON_ARRAY('上传图片', '选调色方向', '选调整幅度', '点生成'),
        'tips', JSON_ARRAY('原图偏色（发黄、发灰）先选「原图校正」', '幅度从「轻微」开始，不满意再往上加'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-color-tune'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '加一层风格滤镜，改的是色彩和光感，不改内容。',
        'steps', JSON_ARRAY('上传图片', '选滤镜风格', '选强度', '点生成'),
        'tips', JSON_ARRAY('强度「轻」通常就够，重了会糊掉细节', '黑白胶片对构图要求高，杂乱背景慎用'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-filter-look'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '重做主光方向和氛围，物体与人的投影会跟着变。',
        'steps', JSON_ARRAY('上传图片', '选氛围', '需要具体光位就写在补充里', '点生成'),
        'tips', JSON_ARRAY('原图有明显的单一光源时最好改', '室内窗光加室外阳光这种多光源场景，改完容易出现两个方向的影子'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-light-mood'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

-- 创意与合成
UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '把主体放进新环境，姿势、服装、外形都不变。',
        'steps', JSON_ARRAY('上传人物或商品图', '描述目标场景', '可再加天气 / 时间', '点生成'),
        'tips', JSON_ARRAY('场景描述带上时间和光线（「清晨薄雾的湖边」）更准', '主体的脚或底部要完整，否则落地会飘'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-scene-swap'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '两张图合成一张：第 1 张是场景，第 2 张是要放进去的主体。',
        'steps', JSON_ARRAY('先传场景底图（第 1 张）', '再传主体图（第 2 张）', '选位置和大小', '点生成'),
        'tips', JSON_ARRAY('两张图的上传顺序会改变生成语义，别放反', '拍摄角度越接近越自然；主体图背景干净时边缘更干净'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-image-merge'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;

UPDATE commerce_tools SET form_schema = JSON_SET(form_schema, '$.guide', JSON_OBJECT(
        'intro', '以图做海报，带标题文字和版式，适合新品、活动、演出。',
        'steps', JSON_ARRAY('上传素材图', '写主题与用途', '填画面文字（会逐字使用）', '选版式风格', '点生成'),
        'tips', JSON_ARRAY('文字越短越不容易写错', '英文长句和生僻字容易出错，出图后务必逐字核对'))
    )
WHERE scene = 'painting' AND tool_key = 'paint-poster'
  AND JSON_VALID(form_schema) AND JSON_EXTRACT(form_schema, '$.guide') IS NULL;
