# SHOEBOX

# --- !Ups

ALTER TABLE image_info ADD COLUMN if not exists path varchar(64) NULL;

insert into evolutions (name, description) values('283.sql', 'adding path to the image info');

# --- !Downs
