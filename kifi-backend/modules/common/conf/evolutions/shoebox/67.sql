# SHOEBOX

# --- !Ups

alter TABLE social_user_info
    add column profile_url varchar(256);

insert into evolutions (name, description) values('67.sql', 'add profile_url to social_user_info');

# --- !Downs
