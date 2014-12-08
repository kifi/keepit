# SHOEBOX

# --- !Ups

ALTER TABLE page_info ADD COLUMN authors VARCHAR(4096) NOT NULL DEFAULT '[]';
ALTER TABLE page_info ADD COLUMN published_at datetime DEFAULT NULL;

insert into evolutions (name, description) values('270.sql', 'adding authors and published_at columns to page_info table');

# --- !Downs
