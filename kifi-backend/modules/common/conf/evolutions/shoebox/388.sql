# SHOEBOX

# --- !Ups

ALTER TABLE library ADD COLUMN organization_member_access varchar(20);

insert into evolutions (name, description) values('388.sql', 'adding organization_member_access column to library');

# --- !Downs
