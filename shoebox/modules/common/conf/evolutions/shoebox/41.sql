# --- !Ups

ALTER TABLE normalized_uri
    DROP COLUMN domain;

insert into evolutions (name, description) values('41.sql', 'remove domain column from normalized uri table');

# --- !Downs
