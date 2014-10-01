#Shoebox

# --- !Ups
ALTER TABLE bookmark ADD COLUMN in_disjoint_lib BOOLEAN DEFAULT TRUE AFTER is_primary;

ALTER TABLE bookmark ADD CONSTRAINT lib_primary_uri UNIQUE (library_id, is_primary, uri_id);
ALTER TABLE bookmark ADD CONSTRAINT user_disjoint_lib_uri UNIQUE (user_id, in_disjoint_lib, uri_id);
ALTER TABLE bookmark DROP CONSTRAINT user_id_primary_uri_id;

-- MySQL:
-- ALTER TABLE bookmark ADD UNIQUE lib_id_primary_uri_id (library_id, is_primary, uri_id);
-- ALTER TABLE bookmark ADD UNIQUE user_id_in_disjoint_lib_uri_id (user_id, in_disjoint_lib, uri_id);
-- ALTER TABLE bookmark DROP KEY user_id_primary_uri_id;

insert into evolutions (name, description) values('253.sql', 'drop user_id_primary_uri_id AND add in_disjoint_lib to bookmark');

# --- !Downs
