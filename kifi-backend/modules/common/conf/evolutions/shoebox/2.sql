
# --- !Ups

alter table user 
  drop column facebook_access_token;
  
alter table user 
  add column social_user varchar(2048); 

insert into evolutions (name, description) values('2.sql', 'replacing facebook token with social user');

# --- !Downs
