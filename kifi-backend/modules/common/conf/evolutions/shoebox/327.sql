# SHOEBOX

# --- !Ups

ALTER TABLE library ADD COLUMN invite_collab varchar(20) NULL;

insert into evolutions (name, description) values('327.sql', 'adding whether collabs can invite other collabs setting to library');

# --- !Downs
