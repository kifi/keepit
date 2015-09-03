# ELIZA

# --- !Ups

ALTER TABLE notification ADD COLUMN external_id varchar(36) DEFAULT NULL;

ALTER TABLE notification_item ADD COLUMN external_id varchar(36) DEFAULT NULL;

ALTER TABLE notification MODIFY COLUMN last_checked datetime NULL DEFAULT NULL;

ALTER TABLE notification_item ADD COLUMN event_time datetime NOT NULL;

ALTER TABLE user_thread ADD COLUMN notification_id INT DEFAULT NULL;
ALTER TABLE user_thread ADD CONSTRAINT user_thread_notification_id FOREIGN KEY (notification_id) REFERENCES notification(id);

insert into evolutions (name, description) values(
  '390.sql',
  'Add external_id to notification and notification item, event_time to notification item, notification_id to user thread'
);

# --- !Downs
