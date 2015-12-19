# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN connections MEDIUMTEXT DEFAULT NULL;
insert into evolutions(name, description) values('420.sql', 'add bookmark.connections');

# --- !Downs
