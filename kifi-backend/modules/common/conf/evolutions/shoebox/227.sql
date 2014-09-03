# CURATOR

# --- !Ups

create index curator_keep_info_i_user_id on curator_keep_info (user_id);

insert into evolutions (name, description) values('227.sql', 'add index on user on curator_keep_info');

# --- !Downs
