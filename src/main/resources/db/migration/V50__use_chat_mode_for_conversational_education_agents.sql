-- V46: consultation and practice agents should open directly in chat.
-- Structured, deliverable-oriented agents keep their task-specific ui_config_json panels.
UPDATE agents
SET ui_config_json = NULL
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
