# Gemini Feasibility Benchmark

## Purpose

This document is the evidence record for the product's highest-risk assumption: that a supported public YouTube video can become a useful, evidence-linked learning package with predictable reliability, latency, and cost. A blank table is not a feasibility result; no MVP investment gate may be passed without data.

## Protocol

1. Use a stratified benchmark of at least 50 approved public videos. Record URL, content right/permission note, language, duration, format, transcript availability, and audio/visual characteristics.
2. Freeze model name/version, prompt version, schema version, generation parameters, and output profile for each run.
3. Have a reviewer compare output to the source. Score factual accuracy, timestamp accuracy, coverage, flashcard usefulness, quiz correctness, and harmful hallucination independently on a defined 1–5 rubric.
4. Record every attempt, including retries and failures. Do not remove failed cases from aggregate rates.
5. Re-run at least 10 representative cases to measure variance.
6. Record actual provider cost and a conservative shadow cost for YouTube URL input; preview/free pricing must never be the business-case assumption.
7. For a learner subset, measure a pre-test, immediate post-test, and delayed recall after 3–7 days against the existing learning method where feasible.

## Scoring rubric

| Dimension | 1 | 3 | 5 |
|---|---|---|---|
| Factual accuracy | Materially wrong/misleading | Mostly accurate, notable omissions or error | Accurate and appropriately qualified |
| Timestamp accuracy | Does not lead to supporting content | Approximate or partially supports claim | Leads to the specific supporting moment |
| Flashcards | Incorrect, vague, or duplicate | Usable but uneven | Clear recall prompts grounded in source |
| Quiz | Wrong/ambiguous answer | Generally usable with issues | Unambiguous, correct, useful explanation |
| Learning usefulness | No practical study value | Helpful summary but passive | Enables comprehension and active recall |

Any serious hallucination, wrong correct answer, or fabricated source timestamp is also tagged as a defect independent of the average score.

## Required run fields

| Run | Video segment | Model/config | Prompt/schema | Status | Latency | Input/output/reasoning tokens | Estimated cost | Valid schema | Accuracy | Timestamp | Flashcards | Quiz | Serious defects | Notes |
|---|---|---|---|---|---:|---|---:|---|---:|---:|---:|---:|---|---|
| 001 | `language / duration / format` |  |  |  |  |  |  |  |  |  |  |  |  |  |

Store video URLs and any sensitive notes in a controlled benchmark dataset if this repository should not expose them. Use a stable run ID here.

The executable protocol lives in `benchmark/README.md`. It reuses the production Gemini adapter,
prompt factory and validation codec; records every retry/failure in an append-only JSONL journal; and
writes a configuration/dataset-hash manifest plus a safe human-review CSV. A partial smoke run is always
marked and cannot pass this gate. Provider-console billing must be reconciled because failed calls may
have billable usage that no response reports.

## Aggregates to publish for every configuration

- Accepted-job and valid-package rate, including failures.
- Median and p95 latency.
- Median and p95 estimated cost per completed package.
- Mean and distribution of each quality score.
- Serious-defect rate.
- Results by language, duration bucket, format, and transcript availability.
- Repeat-run variance.
- Learning gain and delayed recall for the learner subset.
- Actual cost and shadow-priced cost by video-minute bucket and processing mode.

## Decision record

| Date | Configuration | Evidence summary | Gate target | Decision | Owner | Follow-up |
|---|---|---|---|---|---|---|
|  |  |  |  | pending |  |  |
