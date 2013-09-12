# SHOEBOX

# --- !Ups

alter TABLE normalized_uri
    add column restriction varchar(32) NULL ;

insert into evolutions (name, description) values('96.sql', 'add restriction context to normalized_uri');

# --- !Downs
