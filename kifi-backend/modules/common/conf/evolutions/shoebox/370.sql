# SHOEBOX

# --- !Ups

ALTER TABLE keep_to_library ADD COLUMN added_at DATETIME NOT NULL;
ALTER TABLE keep_to_library ADD COLUMN uri_id BIGINT(20) NOT NULL;
ALTER TABLE keep_to_library ADD COLUMN is_primary TINYINT(1) NOT NULL;
ALTER TABLE keep_to_library ADD COLUMN keep_owner BIGINT(20) NOT NULL;
ALTER TABLE keep_to_library ADD COLUMN visibility VARCHAR(32) NOT NULL;
ALTER TABLE keep_to_library ADD COLUMN organization_id BIGINT(20) DEFAULT NULL;

insert into evolutions (name, description) values('370.sql', 'add denormalized fields to keep_to_library'); 

# --- !Downs
