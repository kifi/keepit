# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD INDEX bookmark_i_organization_id (organization_id);
insert into evolutions (name, description) values('383.sql', 'add index on bookmark.organization_id');

# --- !Downs
