# SHOEBOX

# --- !Ups
alter table social_user_info
    add column username varchar(64) NULL;

CREATE UNIQUE INDEX social_user_info_u_username ON social_user_info(network_type, username);

insert into evolutions (name, description) values('315.sql', 'add username column to social user info and an index');

# --- !Downs
