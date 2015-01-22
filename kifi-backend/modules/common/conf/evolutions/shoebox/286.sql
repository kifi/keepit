# SHOEBOX

# --- !Ups

ALTER TABLE image_info modify COLUMN path varchar(64) NOT NULL;

insert into evolutions (name, description) values('286.sql', 'making path in image info be NOT NULL');

# --- !Downs
