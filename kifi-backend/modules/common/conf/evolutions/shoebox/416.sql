# SHOEBOX

# --- !Ups

ALTER TABLE library ADD COLUMN who_can_comment VARCHAR(20) NOT NULL DEFAULT 'collaborator';
insert into evolutions(name, description) values('416.sql', 'add library.who_can_comment');

# --- !Downs
