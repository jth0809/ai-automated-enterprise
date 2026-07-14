-- The formal Phase 2 operations API accepts a bounded 2,000-character human
-- release rationale. Preserve V9 immutability and widen only this audit field.
ALTER TABLE ops_action_log MODIFY reason VARCHAR2(2000);
