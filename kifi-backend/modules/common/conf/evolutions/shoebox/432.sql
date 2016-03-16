# SHOEBOX

# --- !Ups

ALTER TABLE bookmark DROP COLUMN organization_id;
ALTER TABLE bookmark DROP COLUMN visibility;

insert into evolutions(name, description) values('432.sql', 'BEGIN THE REAPING (i.e., drop organization_id from bookmark)');

# --- !Downs
