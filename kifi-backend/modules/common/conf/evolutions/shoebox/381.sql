# ABOOK

# --- !Ups

ALTER TABLE bookmark ADD COLUMN `entities_hash` int(10) DEFAULT NULL; -- for now!
ALTER TABLE bookmark ADD INDEX bookmark_i_entities_hash (entities_hash);

insert into evolutions (name, description) values('381.sql', 'add unique column bookmark.entities_hash');

# --- !Downs
