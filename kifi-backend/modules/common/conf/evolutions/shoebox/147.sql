# SHOEBOX

# --- !Ups

<<<<<<< HEAD
ALTER TABLE bookmark MODIFY url varchar(3072) NOT NULL;

insert into evolutions (name, description) values('147.sql', 'updating url size of bookmark');

# --- !Downs
