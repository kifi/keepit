# CURATOR

# --- !Ups

ALTER TABLE curator_keep_info
  ADD COLUMN library_id BIGINT(20) NULL;

insert into evolutions (name, description) values('238.sql', 'add library id column in curator_keep_info');

# --- !Downs
