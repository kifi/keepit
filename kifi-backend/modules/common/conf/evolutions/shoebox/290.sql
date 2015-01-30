# SHOEBOX

# --- !Ups

ALTER TABLE persona add COLUMN display_name varchar(32) NOT NULL;
ALTER TABLE persona add COLUMN icon_path varchar(50) NOT NULL;
ALTER TABLE persona add COLUMN active_icon_path varchar(50) NOT NULL;

insert into evolutions (name, description) values('290.sql', 'adding display_name & icon URL paths to persona table');

# --- !Downs
