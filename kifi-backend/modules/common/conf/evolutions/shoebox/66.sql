# --- !Ups

alter TABLE social_user_info
    add column picture_url varchar(256);

insert into evolutions (name, description) values('66.sql', 'add picture_url to social_user_info');

# --- !Downs
