# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN `connections_hash` int(10) DEFAULT NULL; -- for now!
ALTER TABLE bookmark ADD INDEX bookmark_i_connections_hash (connections_hash);

insert into evolutions (name, description) values('381.sql', 'add unique column bookmark.connections_hash');

# --- !Downs
