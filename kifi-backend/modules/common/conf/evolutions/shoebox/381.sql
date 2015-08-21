# ABOOK

# --- !Ups

ALTER TABLE bookmark ADD COLUMN `entities_hash` bigint(20) DEFAULT NULL; -- for now!
ALTER TABLE bookmark ADD UNIQUE INDEX bookmark_u_entities_hash (entities_hash); -- is unique a good idea?

insert into evolutions (name, description) values('381.sql', 'add unique column bookmark.entities_hash');

# --- !Downs
