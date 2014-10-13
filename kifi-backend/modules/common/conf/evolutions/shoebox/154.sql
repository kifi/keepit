# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD is_primary BOOLEAN DEFAULT TRUE;

-- MySQL:
-- ALTER TABLE bookmark DROP KEY user_id;
-- ALTER TABLE bookmark DROP KEY user_id_2;

-- H2:
ALTER TABLE bookmark DROP CONSTRAINT IF EXISTS user_id;
ALTER TABLE bookmark DROP CONSTRAINT IF EXISTS user_id_2;

insert into evolutions (name, description) values('154.sql', 'add is_primary and constraint to bookmark');

# --- !Downs
