
# --- !Ups

alter table user 
  drop column facebook_access_token;
  
alter table user 
  add column social_user varchar(2048); 

# --- !Downs
