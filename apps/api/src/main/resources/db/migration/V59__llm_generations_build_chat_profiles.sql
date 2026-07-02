ALTER TABLE llm_generations
  DROP CONSTRAINT chk_llm_generations_profile;

ALTER TABLE llm_generations
  ADD CONSTRAINT chk_llm_generations_profile
  CHECK (ai_profile IN (
    'AS_CHAT_FAST',
    'AS_CHAT_54_FAST',
    'AS_CHAT_54_MINI_FAST',
    'AS_CHAT_NANO_FAST',
    'AS_CHAT_BALANCED',
    'AS_CHAT_HIGH_QUALITY',
    'BUILD_CHAT_FAST',
    'BUILD_CHAT_54_FAST',
    'BUILD_CHAT_54_MINI_FAST'
  ));
