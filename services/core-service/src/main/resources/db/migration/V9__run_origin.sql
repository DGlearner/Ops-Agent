-- Which chat run a remediation, its verification and an entry came from (docs/agent-design.md §15.2). Kept for
-- tracing only, decided on by nothing; empty for what did not come through the assistant.
ALTER TABLE remediations ADD COLUMN run_id text;
ALTER TABLE remediations ADD COLUMN session_id text;
ALTER TABLE remediations ADD COLUMN snapshot_digest text;
ALTER TABLE verifications ADD COLUMN run_id text;
ALTER TABLE case_entries ADD COLUMN run_id text;
