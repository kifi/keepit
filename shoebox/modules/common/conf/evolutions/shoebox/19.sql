# --- !Ups

alter table social_user_info 
  add column last_graph_refresh datetime NULL; 
             
insert into evolutions (name, description) values('19.sql', 'adding social_user_info last_graph_refresh');

# --- !Downs
