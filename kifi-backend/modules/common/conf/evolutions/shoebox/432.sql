# SHOEBOX

# --- !Ups

ALTER TABLE bookmark DROP CONSTRAINT bookmark_f_library_id;
ALTER TABLE bookmark DROP CONSTRAINT lib_primary_uri;
DROP INDEX bookmark_i_library_id_kept_at;

ALTER TABLE bookmark DROP COLUMN organization_id;
ALTER TABLE bookmark DROP COLUMN visibility;
ALTER TABLE bookmark DROP COLUMN library_id;

insert into evolutions(name, description) values('432.sql', 'BEGIN THE REAPING (i.e., drop library_id, visibility, organization_id from bookmark)');

# --- !Downs
