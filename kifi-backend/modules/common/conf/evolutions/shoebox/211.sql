# CURATOR

# --- !Ups

CREATE INDEX raw_seed_item_i_user_id ON raw_seed_item(user_id);

insert into evolutions (name, description) values('211.sql', 'add user_id index to raw_seed_item');

# --- !Downs
