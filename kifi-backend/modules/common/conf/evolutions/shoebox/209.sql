# SHOEBOX

# --- !Ups

ALTER TABLE library ADD COLUMN member_count int NOT NULL DEFAULT 1;

ALTER TABLE library DROP COLUMN is_searchable_by_others;

insert into evolutions (name, description) values('209.sql', 'member_count in library, drop column ');

# --- !Downs
