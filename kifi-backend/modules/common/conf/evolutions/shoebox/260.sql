#Shoebox

# --- !Ups

ALTER TABLE collection ADD UNIQUE INDEX collection_u_user_id_name (user_id, name);
DROP INDEX collection_i_user_id_name;

insert into evolutions (name, description) values('260.sql', 'add unique constraint on (user_id, name) for collection');

# --- !Downs
