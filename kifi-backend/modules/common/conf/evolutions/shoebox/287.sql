# SHOEBOX

# --- !Ups

ALTER TABLE page_info ADD COLUMN og_type VARCHAR(128);

insert into evolutions (name, description) values('287.sql', 'adding og_type to page_info');

# --- !Downs
