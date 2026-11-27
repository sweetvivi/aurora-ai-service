-- Audio is transcribed before chat; this panel accepts common speech-recording formats.
UPDATE agents
SET ui_config_json = '{"enabled":true,"title":"开始英语口语练习","description":"选择水平和场景；录音入口预留，等待适配的音频模型服务接入。","icon":"ri-mic-line","submitLabel":"开始对话","fields":[{"key":"level","label":"英语水平","type":"select","required":true,"options":["入门","基础","中级","高级"]},{"key":"scene","label":"练习场景","type":"choice","required":true,"options":["日常通勤","酒店入住","商务会议","约会聚餐","面试求职"]},{"key":"goal","label":"本次目标","type":"text","required":true},{"key":"correction","label":"纠错方式","type":"select","options":["对话后集中纠正","每轮即时纠正","只给更自然表达"]},{"key":"recording","label":"口语录音（预留）","type":"upload","accept":".mp3,.wav,.m4a,.ogg,.webm,audio/*","maxFiles":1,"helpText":"音频端点尚未适配，接入前上传录音暂不能用于反馈"}]}'
WHERE agent_key = 'education-english-speaking-partner';

UPDATE agents
SET system_prompt = CONCAT(system_prompt, '\n\n录音能力边界：用户上传录音时，你会收到自动语音转写文本。请基于转写内容提供词汇、语法和表达建议；转写可能有误，应说明不确定处。平台当前不提供可靠的口音或音素级发音评分，不得声称已听辨口音、语调或发音细节。')
WHERE agent_key = 'education-english-speaking-partner';
