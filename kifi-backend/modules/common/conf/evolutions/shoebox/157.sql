# SHOEBOX

# --- !Ups

ALTER TABLE image_info MODIFY name varchar(256) NOT NULL;
ALTER TABLE image_info ADD provider varchar(36), format varchar(36), priority int;

insert into evolutions (name, description) values('157.sql', 'modify image_info table');

# --- !Downs
