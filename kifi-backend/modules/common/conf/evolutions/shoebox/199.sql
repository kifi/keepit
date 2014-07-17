# SHOEBOX

# --- !Ups

ALTER TABLE library ADD COLUMN kind varchar(64) NOT NULL;

insert into evolutions (name, description) values('199.sql', 'add library kind column');

# --- !Downs
