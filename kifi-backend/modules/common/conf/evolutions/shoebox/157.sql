# SHOEBOX

# --- !Ups

ALTER TABLE image_info MODIFY COLUMN name varchar(256) NOT NULL;
ALTER TABLE image_info MODIFY COLUMN url varchar(2048);
ALTER TABLE image_info ADD COLUMN if not exists provider varchar(36);
ALTER TABLE image_info ADD COLUMN if not exists format varchar(36);
ALTER TABLE image_info ADD COLUMN if not exists priority int;

insert into evolutions (name, description) values('157.sql', 'modify image_info table');

# --- !Downs
