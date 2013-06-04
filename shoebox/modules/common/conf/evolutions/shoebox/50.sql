# ALTER TABLE bookmark MODIFY COLUMN title VARCHAR(2048) NULL;

# --- !Ups

ALTER TABLE bookmark ALTER COLUMN title SET NULL;

insert into evolutions (name, description) values('50.sql', 'make bookmark title nullable');

# --- !Downs
