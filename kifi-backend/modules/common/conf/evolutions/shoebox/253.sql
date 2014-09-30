#Shoebox

# --- !Ups
ALTER TABLE bookmark ADD CONSTRAINT lib_id_primary_uri_id (library_id, is_primary, uri_id);
ALTER TABLE bookmark DROP CONSTRAINT user_id_primary_uri_id;

-- MySQL:
-- ALTER TABLE bookmark ADD UNIQUE lib_id_primary_uri_id (library_id, is_primary, uri_id);
-- ALTER TABLE bookmark DROP KEY user_id_primary_uri_id;

ALTER TABLE bookmark ADD COLUMN main_or_secret BOOLEAN DEFAULT TRUE AFTER is_primary;

insert into evolutions (name, description) values('253.sql', 'replace user_id_primary_uri_id with lib_id_primary_id; add main_or_secret to bookmark');

# --- !Downs
