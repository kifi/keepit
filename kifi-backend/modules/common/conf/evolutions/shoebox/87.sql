# ELIZA

# --- !Ups
  
-- Prod versions for millisecond precicion in mysql
-- alter table message_thread modify column created_at datetime(3) NOT NULL; 
-- alter table message_thread modify column updated_at datetime(3) NOT NULL; 

-- alter table message modify column created_at datetime(3) NOT NULL; 
-- alter table message modify column updated_at datetime(3) NOT NULL; 

-- alter table user_thread modify column created_at datetime(3) NOT NULL; 
-- alter table user_thread modify column updated_at datetime(3) NOT NULL; 
-- alter table user_thread modify column last_seen datetime(3) NULL;
-- alter table user_thread modify column notification_updated_at datetime(3) NOT NULL;
-- alter table user_thread modify column notification_last_seen datetime(3) NULL;

insert into evolutions (name, description) values('87.sql', 'millisecond precision for datetimes');

# --- !Downs
