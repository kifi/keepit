# SHOEBOX

# --- !Ups

alter TABLE normalized_uri
    add column sensitivity varchar(32) NULL ;

alter TABLE bookmark
    add column is_sensitive boolean NOT NULL DEFAULT FALSE;

insert into evolutions (name, description) values('95.sql', 'add sensitivity information to normalized_uri and bookmark');

# --- !Downs
