# --- !Ups

alter TABLE user_notification
  add column subsumed_id bigint(20);

alter TABLE user_notification
  add CONSTRAINT user_notification_f_subsumed_id FOREIGN KEY (subsumed_id) REFERENCES user_notification(id);

insert into evolutions (name, description) values('52.sql', 'adding subsumed column to user_notification');

# --- !Downs
