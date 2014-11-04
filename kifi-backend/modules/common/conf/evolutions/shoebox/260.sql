#Shoebox

# --- !Ups

ALTER TABLE collection ADD CONSTRAINT collection_u_user_id_name UNIQUE (user_id, name);

insert into evolutions (name, description) values('260.sql', 'add unique constraint on (user_id, name) for collection');

# --- !Downs
