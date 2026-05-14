-- ============================================
-- Database Initialization Script
-- Real-Time Inventory & Recommendation System
-- ============================================

-- Create audit log tables with append-only enforcement
-- Application user cannot UPDATE or DELETE from audit tables

-- The main schema is managed by Spring JPA/Hibernate (ddl-auto: update)
-- This script only handles security constraints that JPA cannot express

-- After Hibernate creates the tables, run these grants:
-- (Executed via a startup hook or manually)

-- Note: These will be applied after tables are created by the app
-- For now, we create the extensions needed

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
