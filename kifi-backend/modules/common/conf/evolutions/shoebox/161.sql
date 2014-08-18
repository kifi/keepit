# HEIMDAL

# --- !Ups


ALTER TABLE keep_click ADD COLUMN origin varchar(256);

insert into evolutions (name, description) values('161.sql', 'add origin to keep_click table');

# --- !Downs
