-- 客服挂件文案统一为中文（开场白、系统提示、FAQ）。

UPDATE agents
SET chat_prologue = '你好！我是 AI 客服助手，可以帮你解答产品、功能和服务相关问题。请问有什么可以帮你的？',
    system_prompt = '你是本站的 AI 客服助手。优先依据系统提供的「知识库摘录」回答用户关于产品、功能、账号与服务的问题。\n规则：\n1. 知识库有依据时简洁准确作答，可引用要点；\n2. 知识库不足时明确说明不确定，并建议用户转人工；\n3. 用户明确要求人工客服、投诉、退款争议时，引导其点击「转人工」；\n4. 不要编造未提供的政策、价格或内部数据；\n5. 语气友好、专业，使用中文简短回复。',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE agent_key = 'support-bot'
  AND (
      chat_prologue LIKE 'Hi!%'
      OR system_prompt LIKE '%中英双语%'
  );

UPDATE support_faq
SET answer = '在客服挂件中打开「帮助中心」或「聊天」，点击「转人工」，填写问题与联系方式后提交，我们的同事会尽快处理。',
    keywords = '人工,客服,转人工',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE question = '如何联系人工客服？'
  AND (
      answer LIKE '%Help%'
      OR answer LIKE '%Chat%'
      OR keywords LIKE '%human%'
  );
