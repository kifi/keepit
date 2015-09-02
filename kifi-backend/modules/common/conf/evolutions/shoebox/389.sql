# ELIZA

# --- !Ups

ALTER TABLE notification ADD COLUMN external_id varchar(36) DEFAULT NULL;

ALTER TABLE notification_item ADD COLUMN external_id varchar(36) DEFAULT NULL;

ALTER TABLE notification MODIFY COLUMN last_checked int NULL;
ALTER TABLE notification ADD CONSTRAINT last_checked_notification_item_id FOREIGN KEY (last_checked) REFERENCES notification_item(id);

ALTER TABLE notification MODIFY COLUMN last_event int NULL;
ALTER TABLE notification ADD CONSTRAINT last_event_notification_item_id FOREIGN KEY (last_event) REFERENCES notification_item(id);

ALTER TABLE user_thread ADD COLUMN notification_id INT NULL;
ALTER TABLE user_thread ADD CONSTRAINT user_thread_notification_id FOREIGN KEY (notification_id) REFERENCES notification(id);

insert into evolutions (name, description) values(
  '389.sql',
  'Add external_id to notification and notification item, notification_id to user thread, make notification last_checked and last_event point to notification_item'
);

# --- !Downs
