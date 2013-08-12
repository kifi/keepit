# --- !Ups
  

create index message_i_ext_id on message (external_id); 
-- create index message_i_ext_id on message (external_id(8)); --Prod version 

create index user_thread_i_user_notifUpdated on user_thread (user_id,notification_updated_at);
create index user_thread_i_user_notifPending on user_thread (user_id,notification_pending);

insert into evolutions (name, description) values('5.sql', 'adding neccessary indices');

# --- !Downs
