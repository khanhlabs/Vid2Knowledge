# Controlled Gemini benchmark

This runner exercises the exact production prompt factory, Gemini interaction adapter, package codec,
schema rules, and AI rate-card arithmetic without starting the application or writing production data.
It refuses decision runs below 50 unique videos plus repeat runs for at least 10 cases, rejects unsupported
URLs, and requires a rights basis and audio/visual characteristics for every case. A decision dataset must
cover Vietnamese and English, all four core formats, all three duration bands, and both transcript states.
The checked-in dataset is structure only; do not commit prospect URLs or private permission notes.

Create a controlled JSON dataset from `dataset.example.json` with at least 50 stratified cases and at
least 10 repeated cases (same `caseId` and URL, unique `runId`) to measure run-to-run variance. Keep it
outside the repository or under ignored `benchmark/private/`. Use a new output directory for every run:

```powershell
$env:GEMINI_API_KEY = '<secret from your password manager>'
./mvnw spring-boot:run "-Dspring-boot.run.main-class=com.vid2knowledge.benchmark.BenchmarkRunner" "-Dspring-boot.run.arguments=D:/secure/v2k-benchmark.json D:/secure/results/run-001"
```

Run the command from `backend/`. Override `GEMINI_MODEL`, `GEMINI_BASE_URL`, `GEMINI_TIMEOUT`,
`BENCHMARK_OUTPUT_PROFILE`, `BENCHMARK_MAX_ATTEMPTS`, and all `AI_ACTUAL_*_MICROUSD` /
`AI_SHADOW_*_MICROUSD` values only when intentionally freezing a new configuration. The manifest stores
the model, prompt/schema version, rates, output profile, dataset SHA-256, and overall plus per-stratum
valid rate, latency, and cost. `run-results.jsonl` is append-only during execution, so provider failures remain in
the evidence. Failed provider calls have unknown billable usage and must be reconciled against the
provider console before approving the cost gate.

Review every generated `*.package.json` against its source and fill `human-review.csv` using the rubric
in `docs/feasibility-result.md`. `BENCHMARK_ALLOW_PARTIAL=true` exists only for a paid-request smoke test;
a partial manifest cannot pass the investment gate.
