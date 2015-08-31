# SHOEBOX

# --- !Ups

ALTER TABLE bookmark DROP COLUMN connections_hash;

ALTER TABLE bookmark ADD COLUMN libraries_hash int(10) DEFAULT NULL; -- for now!
ALTER TABLE bookmark ADD COLUMN participants_hash int(10) DEFAULT NULL; -- for now!

ALTER TABLE bookmark ADD INDEX bookmark_i_uri_id_libraries_hash (uri_id, libraries_hash);

insert into evolutions (name, description) values('386.sql', 'split bookmark.connections_hash into participants_hash and libraries_hash');

# --- !Downs
