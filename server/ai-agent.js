const OPENAI_RESPONSES_URL = 'https://api.openai.com/v1/responses';

export const SOUYAKU_AI_MODEL = process.env.SOUYAKU_AI_MODEL || 'gpt-5.6-luna';
export const SOUYAKU_AI_AGENT_NAME = 'SOUYAKU Agent';

const MAX_TURNS = 16;
const MAX_TEXT_CHARS = 2400;

const ACTIONS = new Set(['auto', 'suggest_reply', 'explain', 'summarize', 'clarify']);
const MODES = new Set(['auto', 'daily', 'travel', 'hotel', 'restaurant', 'shopping', 'business']);

const responseSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    agent_message: { type: 'string' },
    detected_intent: { type: 'string' },
    summary: { type: 'string' },
    explanation: { type: 'string' },
    ambiguity_detected: { type: 'boolean' },
    ambiguity_reason: { type: 'string' },
    clarification_question: { type: 'string' },
    reply_options: {
      type: 'array',
      maxItems: 3,
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          text: { type: 'string' },
          partner_text: { type: 'string' },
          tone: { type: 'string' },
        },
        required: ['text', 'partner_text', 'tone'],
      },
    },
  },
  required: [
    'agent_message',
    'detected_intent',
    'summary',
    'explanation',
    'ambiguity_detected',
    'ambiguity_reason',
    'clarification_question',
    'reply_options',
  ],
};

function cleanText(value, maxChars = MAX_TEXT_CHARS) {
  if (typeof value !== 'string') return '';
  return value.replace(/\u0000/g, '').trim().slice(0, maxChars);
}

function cleanLanguage(value) {
  return cleanText(value, 48);
}

function cleanSpeaker(value) {
  return value === 'self' ? 'self' : 'partner';
}

function sanitizeTurn(turn) {
  if (!turn || typeof turn !== 'object') return null;
  const originalText = cleanText(turn.originalText);
  const translatedText = cleanText(turn.translatedText);
  if (!originalText && !translatedText) return null;
  return {
    speaker: cleanSpeaker(turn.speaker),
    sourceLanguage: cleanLanguage(turn.sourceLanguage),
    targetLanguage: cleanLanguage(turn.targetLanguage),
    originalText,
    translatedText,
  };
}

function sanitizeRequest(payload) {
  const action = ACTIONS.has(payload?.action) ? payload.action : 'auto';
  const mode = MODES.has(payload?.mode) ? payload.mode : 'auto';
  const conversation = Array.isArray(payload?.conversation)
    ? payload.conversation.map(sanitizeTurn).filter(Boolean).slice(-MAX_TURNS)
    : [];
  return {
    action,
    mode,
    ownLanguage: cleanLanguage(payload?.ownLanguage) || 'unknown',
    partnerLanguage: cleanLanguage(payload?.partnerLanguage) || 'unknown',
    latestOriginal: cleanText(payload?.latestOriginal),
    latestTranslation: cleanText(payload?.latestTranslation),
    conversation,
  };
}

function instructions() {
  return [
    'You are SOUYAKU Agent, the conversation-support intelligence inside a bidirectional interpreter app.',
    'Your job is not to replace the deterministic translation pipeline. You assist a human user with context.',
    'Never claim that you sent a message, booked something, called someone, changed settings, or performed an external action.',
    'Do not invent missing facts. If meaning depends on missing context, mark ambiguity_detected=true and provide one concise clarification question.',
    'Treat translatedText as a translation that can contain recognition or translation errors. Prefer the original utterance when it is understandable.',
    'All user-facing explanation, summary, agent_message, ambiguity_reason and clarification_question must use the user ownLanguage.',
    'For reply_options, text must use ownLanguage and partner_text must use partnerLanguage. Keep each option natural and short enough to say aloud.',
    'If partnerLanguage is unknown, set partner_text to an empty string rather than guessing a language.',
    'For action=suggest_reply, prioritize 1 to 3 practical replies. For explain, explain the latest utterance and idiom/context. For summarize, summarize decisions, requests and open questions. For clarify, focus on ambiguity. For auto, choose the most useful mix.',
    'Do not expose chain-of-thought or private reasoning. Return only the requested structured result.',
  ].join('\n');
}

function extractOutputText(response) {
  if (!response || !Array.isArray(response.output)) return '';
  for (const item of response.output) {
    if (!item || !Array.isArray(item.content)) continue;
    for (const part of item.content) {
      if (part?.type === 'output_text' && typeof part.text === 'string') return part.text;
    }
  }
  return '';
}

export async function runSouyakuAgent(payload) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    const error = new Error('OPENAI_API_KEY is not configured');
    error.code = 'ai_not_configured';
    throw error;
  }

  const input = sanitizeRequest(payload);
  if (input.conversation.length === 0 && !input.latestOriginal && !input.latestTranslation) {
    const error = new Error('Conversation text is required');
    error.code = 'empty_conversation';
    throw error;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 22_000);
  try {
    const apiResponse = await fetch(OPENAI_RESPONSES_URL, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'authorization': `Bearer ${apiKey}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: SOUYAKU_AI_MODEL,
        store: false,
        instructions: instructions(),
        input: [
          {
            role: 'user',
            content: [
              {
                type: 'input_text',
                text: JSON.stringify(input),
              },
            ],
          },
        ],
        reasoning: { effort: 'low' },
        max_output_tokens: 1600,
        text: {
          verbosity: 'low',
          format: {
            type: 'json_schema',
            name: 'souyaku_agent_result',
            description: 'Structured SOUYAKU conversation assistance.',
            strict: true,
            schema: responseSchema,
          },
        },
      }),
    });

    const data = await apiResponse.json().catch(() => ({}));
    if (!apiResponse.ok) {
      const error = new Error(data?.error?.message || `OpenAI API HTTP ${apiResponse.status}`);
      error.code = data?.error?.code || 'openai_error';
      error.status = apiResponse.status;
      throw error;
    }

    const outputText = extractOutputText(data);
    if (!outputText) {
      const error = new Error('AI returned no structured output');
      error.code = 'empty_ai_output';
      throw error;
    }

    let agent;
    try {
      agent = JSON.parse(outputText);
    } catch {
      const error = new Error('AI output was not valid JSON');
      error.code = 'invalid_ai_output';
      throw error;
    }

    return {
      agentName: SOUYAKU_AI_AGENT_NAME,
      model: SOUYAKU_AI_MODEL,
      action: input.action,
      result: agent,
    };
  } catch (error) {
    if (error?.name === 'AbortError') {
      const timeoutError = new Error('AI request timed out');
      timeoutError.code = 'ai_timeout';
      throw timeoutError;
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
