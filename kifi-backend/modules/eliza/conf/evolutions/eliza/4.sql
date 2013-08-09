# --- !Ups
  
alter table message_thread modify column created_at datetime(3) NOT NULL; 
alter table message_thread modify column updated_at datetime(3) NOT NULL; 

alter table message modify column created_at datetime(3) NOT NULL; 
alter table message modify column updated_at datetime(3) NOT NULL; 

alter table user_thread modify column created_at datetime(3) NOT NULL; 
alter table user_thread modify column updated_at datetime(3) NOT NULL; 
alter table user_thread modify column last_seen datetime(3) NOT NULL;
alter table user_thread modify column notification_updated_at datetime(3) NOT NULL;
alter table user_thread modify column notification_last_seen datetime(3) NOT NULL;

insert into evolutions (name, description) values('4.sql', 'millisecond precision for datetimes');

# --- !Downs
