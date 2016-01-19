# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN last_activity_at DATETIME NOT NULL;
ALTER TABLE bookmark MODIFY COLUMN connections MEDIUMTEXT NOT NULL;
insert into evolutions(name, description) values('423.sql', 'add last_activity_at to bookmark');

# --- !Downs
