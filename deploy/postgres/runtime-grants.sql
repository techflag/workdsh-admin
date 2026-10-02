-- Run as the schema/database maintenance owner after schema initialization.
-- Create workdsh_app with a deployment-managed password before this script.
-- Deliberately excludes CREATE, ownership and role/database administration.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO workdsh_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO workdsh_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO workdsh_app;

-- Schema history is maintenance-owned; runtime cannot alter migration evidence.
REVOKE INSERT, UPDATE, DELETE ON public.workdsh_schema_history FROM workdsh_app;
