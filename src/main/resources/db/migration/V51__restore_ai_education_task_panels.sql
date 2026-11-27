-- V47: AI Education keeps a form-first experience across the whole workspace.
-- Restore the task-specific panels removed by V46. The generic learning assistant
-- remains immediately usable: its context fields are helpful but no longer required.
UPDATE agents
SET ui_config_json = CASE agent_key
    WHEN 'education-math-tutor' THEN '{"enabled":true,"title":"开始数学辅导","description":"上传题目或粘贴你的解题步骤，智能体会优先给提示。","icon":"ri-function-line","submitLabel":"开始辅导","fields":[{"key":"stage","label":"年级 / 学段","type":"select","required":true,"options":["小学","初中","高中","大学及以上"]},{"key":"topic","label":"知识点","type":"text","required":true,"placeholder":"例如：二次函数、几何证明"},{"key":"attempt","label":"已尝试步骤","type":"textarea","placeholder":"写出你的思路，便于定位卡点"},{"key":"questionImage","label":"上传题目图片","type":"upload","accept":"image/*","maxFiles":2,"helpText":"支持 JPG、PNG"}]}'
    WHEN 'education-english-grammar-coach' THEN '{"enabled":true,"title":"练习英语语法","description":"选择语法主题，提交例句或练习答案。","icon":"ri-translate-2","submitLabel":"开始练习","fields":[{"key":"level","label":"英语水平","type":"select","required":true,"options":["小学","初中","高中","大学及以上"]},{"key":"grammar","label":"语法主题","type":"choice","required":true,"options":["时态","从句","非谓语","冠词","介词","其他"]},{"key":"content","label":"例句或答案","type":"textarea","required":true,"placeholder":"粘贴需要讲解或批改的内容"}]}'
    WHEN 'education-physics-concept-tutor' THEN '{"enabled":true,"title":"解释物理概念","description":"说明困惑的概念或上传题图，按现象、模型和公式讲解。","icon":"ri-speed-up-line","submitLabel":"开始讲解","fields":[{"key":"stage","label":"年级 / 学段","type":"select","required":true,"options":["初中","高中","大学及以上"]},{"key":"concept","label":"概念 / 章节","type":"text","required":true,"placeholder":"例如：动量守恒、失重"},{"key":"question","label":"具体困惑","type":"textarea","required":true},{"key":"image","label":"上传题图","type":"upload","accept":"image/*","maxFiles":2}]}'
    WHEN 'education-parent-child-communication' THEN '{"enabled":true,"title":"梳理亲子沟通","description":"先描述事实和双方需求，再生成可以直接使用的表达句式。","icon":"ri-home-heart-line","submitLabel":"获取建议","fields":[{"key":"stage","label":"孩子学段","type":"select","required":true,"options":["幼儿园","小学","初中","高中","大学及以上"]},{"key":"scene","label":"发生场景","type":"text","required":true},{"key":"concern","label":"具体情况与担心","type":"textarea","required":true},{"key":"goal","label":"希望改变什么","type":"textarea"}]}'
    WHEN 'education-study-habit-coach' THEN '{"enabled":true,"title":"培养学习习惯","description":"选择想改善的习惯，拆成小步行动和记录方式。","icon":"ri-timer-line","submitLabel":"生成计划","fields":[{"key":"stage","label":"年龄 / 学段","type":"select","required":true,"options":["小学","初中","高中","大学及以上"]},{"key":"habit","label":"想改善的习惯","type":"choice","required":true,"options":["按时作业","专注学习","整理错题","规律作息","自主复习"]},{"key":"schedule","label":"当前作息","type":"textarea","required":true},{"key":"barrier","label":"主要阻碍","type":"textarea"}]}'
    WHEN 'education-teen-dialogue-helper' THEN '{"enabled":true,"title":"支持青春期沟通","description":"围绕具体场景提供倾听、表达和共同制定规则的方法。","icon":"ri-user-heart-line","submitLabel":"获取建议","fields":[{"key":"stage","label":"学生阶段","type":"select","required":true,"options":["小学高年级","初中","高中","大学及以上"]},{"key":"scene","label":"沟通主题","type":"choice","required":true,"options":["学习成绩","手机使用","同伴关系","情绪变化","作息安排"]},{"key":"situation","label":"发生了什么","type":"textarea","required":true},{"key":"attempted","label":"已经尝试过的方式","type":"textarea"}]}'
    WHEN 'education-art-appreciation' THEN '{"enabled":true,"title":"开始艺术鉴赏","description":"上传作品或填写作品名称，从观察、形式和背景开始分析。","icon":"ri-artboard-line","submitLabel":"开始鉴赏","fields":[{"key":"audience","label":"适合年龄","type":"select","options":["儿童","青少年","成人"]},{"key":"work","label":"作品名称 / 类型","type":"text","required":true},{"key":"focus","label":"鉴赏重点","type":"choice","options":["构图色彩","媒介技法","历史背景","适合孩子理解"]},{"key":"image","label":"上传作品图片","type":"upload","accept":"image/*","maxFiles":2}]}'
    WHEN 'education-career-exploration' THEN '{"enabled":true,"title":"探索生涯方向","description":"通过兴趣、优势和小型实践任务探索方向，不做决定性诊断。","icon":"ri-compass-4-line","submitLabel":"开始探索","fields":[{"key":"stage","label":"年级 / 年龄","type":"select","required":true,"options":["小学高年级","初中","高中","大学及以上"]},{"key":"interest","label":"感兴趣的事情","type":"textarea","required":true},{"key":"strength","label":"觉得有成就感的经历","type":"textarea","required":true},{"key":"constraint","label":"现实限制","type":"textarea"}]}'
    WHEN 'education-mental-health-literacy' THEN '{"enabled":true,"title":"了解心理健康主题","description":"提供一般科普与低风险支持建议，不进行诊断或治疗。","icon":"ri-heart-pulse-line","submitLabel":"开始了解","fields":[{"key":"audience","label":"面向对象","type":"select","required":true,"options":["儿童","青少年","家长","教师","成人"]},{"key":"topic","label":"想了解的主题","type":"choice","required":true,"options":["考试压力","情绪识别","睡眠与作息","同伴关系","求助渠道"]},{"key":"situation","label":"具体情境","type":"textarea"},{"key":"safety","label":"需要求助资源提示","type":"checkbox","defaultValue":true}]}'
    WHEN 'education-english-speaking-partner' THEN '{"enabled":true,"title":"开始英语口语练习","description":"选择水平和场景，智能体会用短轮次陪练并纠正关键表达。","icon":"ri-mic-line","submitLabel":"开始对话","fields":[{"key":"level","label":"英语水平","type":"select","required":true,"options":["入门","基础","中级","高级"]},{"key":"scene","label":"练习场景","type":"choice","required":true,"options":["日常通勤","酒店入住","商务会议","约会聚餐","面试求职"]},{"key":"goal","label":"本次目标","type":"text","required":true},{"key":"correction","label":"纠错方式","type":"select","options":["对话后集中纠正","每轮即时纠正","只给更自然表达"]}]}'
    WHEN 'education-japanese-beginner' THEN '{"enabled":true,"title":"开始日语入门","description":"选择当前假名基础和生活场景，按进度进行短练习。","icon":"ri-translate-2","submitLabel":"开始练习","fields":[{"key":"kana","label":"假名基础","type":"choice","required":true,"options":["完全没学过","认识平假名","平假名片假名都学过","能读简单单词"]},{"key":"goal","label":"学习目标","type":"select","required":true,"options":["五十音入门","生活会话","旅行日语","基础阅读"]},{"key":"scene","label":"练习场景","type":"choice","options":["便利店","自我介绍","点餐","问路","日常寒暄"]},{"key":"question","label":"想重点练习的内容","type":"textarea"}]}'
    ELSE ui_config_json
END
WHERE agent_key IN (
    'education-math-tutor',
    'education-english-grammar-coach',
    'education-physics-concept-tutor',
    'education-parent-child-communication',
    'education-study-habit-coach',
    'education-teen-dialogue-helper',
    'education-art-appreciation',
    'education-career-exploration',
    'education-mental-health-literacy',
    'education-english-speaking-partner',
    'education-japanese-beginner'
);

UPDATE agents
SET ui_config_json = '{"enabled":true,"title":"描述你的教育需求","description":"可以直接说学习、备课、作业、沟通或计划方面的问题。","icon":"ri-graduation-cap-line","submitLabel":"开始咨询","fields":[{"key":"role","label":"你的身份","type":"choice","options":["学生","教师","家长","其他"]},{"key":"goal","label":"想解决什么","type":"textarea","placeholder":"例如：帮我为初二英语期末复习安排一周计划"},{"key":"context","label":"补充背景","type":"textarea","placeholder":"可选：年级、学科、时间、已有材料或限制条件"}]}'
WHERE agent_key = 'education-learning-assistant';
