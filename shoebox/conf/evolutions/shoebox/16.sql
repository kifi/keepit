# --- !Ups

alter table social_user_info 
  add column lastGraphRefresh datetime NOT NULL; 

insert into evolutions (name, description) values('16.sql', 'adding social_user_info lastGraphRefresh');

# --- !Downs
