# ELIZA

# --- !Ups

ALTER TABLE notification MODIFY COLUMN last_checked DATETIME NULL DEFAULT NULL;

insert into evolutions (name, description) values(
  '392.sql',
  'Make notification last_checked extra super null'
);

# --- !Downs
