# SHOEBOX

# --- !Ups

ALTER TABLE normalized_uri ADD COLUMN should_have_content boolean DEFAULT false;
ALTER TABLE normalized_uri DROP COLUMN is_private;
ALTER TABLE normalized_uri DROP COLUMN screenshot_updated_at;

insert into evolutions (name, description) values('329.sql', 'add should_have_content column to normalized_uri, drop deprecated columns');

# --- !Downs
