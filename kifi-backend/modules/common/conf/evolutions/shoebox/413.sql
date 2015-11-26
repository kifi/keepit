# ELIZA

# --- !Ups

ALTER TABLE notification ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'active' AFTER updated_at;
ALTER TABLE message_thread ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'active' AFTER updated_at;
ALTER TABLE user_thread ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'active' AFTER updated_at;
ALTER TABLE message ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'active' AFTER updated_at;

insert into evolutions(name, description) values('413.sql', 'added a state to notification, message_thread, user_thread, and message');

# --- !Downs
