# --- !Ups

alter table social_user_info 
  add column lastGraphRefresh datetime NULL; 
             
insert into evolutions (name, description) values('17.sql', 'adding social_user_info lastGraphRefresh');

# --- !Downs
