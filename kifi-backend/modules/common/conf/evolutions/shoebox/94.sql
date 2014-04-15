#Shoebox

# --- !Ups
-- alter table bookmark add CONSTRAINT user_id_2 UNIQUE (user_id, uri_id);

insert into evolutions (name, description) values('94.sql', 'adding unique key constrain to bookmark');

# --- !Downs
