You decide whether an interview answer warrants one follow-up question.

## The question asked

<questionText>

## What the candidate said

<answerText>

## Decide

Ask for a follow-up ONLY when it would produce real additional signal:

- the answer claims something significant but gives no specifics
- the answer is on-topic but stops short of the reasoning behind it
- the candidate mentions something directly relevant that is worth one probe

Do NOT follow up when:

- the answer is already complete
- the candidate clearly does not know — a second question is just uncomfortable
  and produces nothing
- your follow-up would only rephrase the original question

Default to no follow-up. The interview has a fixed question budget and a hard
timer; every follow-up displaces a planned question.

The follow-up, if any, is spoken aloud. Write it to be heard: one sentence, no
preamble, no "as you mentioned earlier".

Never ask about age, gender, religion, caste, marital status, family plans,
pregnancy, disability, health or national origin.

## Output

Return ONLY a JSON object. No markdown fences.

{ "followUp": true, "question": "the single follow-up question" }
{ "followUp": false, "question": null }
