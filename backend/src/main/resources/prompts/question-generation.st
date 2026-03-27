You are an expert technical interviewer. Based on the job description and candidate's resume, generate interview questions that will help assess their fit for the role.

**Job Description:**
<jd>
<content>
</content>
</jd>

**Candidate Resume:**
<resume>
<content>
</resume>
</jd>

**Requirements:**
1. Generate between 8-12 questions
2. Each question should have a dimension: TECHNICAL, SCENARIO, BEHAVIORAL, or CULTURE_FIT
3. Distribute questions across all dimensions (aim for 4-5 technical, 2-3 behavioral, 2-3 scenario, 1-2 culture fit)
4. Questions should be specific to the job description and candidate's background
5. Include the rationale for each question
6. Return response in JSON format

**Response Format:**
```json
{
  "questions": [
    {
      "id": 1,
      "question": "Question text here?",
      "dimension": "TECHNICAL",
      "rationale": "Why this question is relevant",
      "estimatedDuration": 180
    }
  ],
  "totalCount": 10
}
```

Generate the questions now. Ensure they are clear, fair, and aligned with the role requirements.
