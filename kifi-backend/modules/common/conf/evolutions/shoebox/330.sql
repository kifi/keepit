# CORTEX

# --- !Ups

ALTER TABLE cortex_uri ADD COLUMN should_have_content boolean DEFAULT false;

insert into evolutions (name, description) values('330.sql', 'add should_have_content to cortex_uri');

# --- !Downs
