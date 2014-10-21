#Shoebox

# --- !Ups

alter table user modify column username varchar(64) NOT NULL;
alter table user modify column normalized_username varchar(64) NOT NULL;

create unique index user_u_username on user(username);
create unique index user_u_normalized_username on user(normalized_username);

drop index user_i_normalized_username;
drop index user_i_username;

insert into evolutions (name, description) values('256.sql', 'not allowing user username and normalized_username to be null');

# --- !Downs
