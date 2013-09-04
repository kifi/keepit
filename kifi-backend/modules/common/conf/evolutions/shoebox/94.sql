#Shoebox

# --- !Ups
alter table bookmark add CONSTRAINT unique_user_uri_pair UNIQUE (user_id, uri_id);

insert into evolutions (name, description) values('94.sql', 'adding unique key constrain to bookmark');

# --- !Downs
