# SHOEBOX

# --- !Ups

alter TABLE raw_keep
  add column tag_id bigint(20) NULL;

alter TABLE raw_keep
  add CONSTRAINT raw_keep_tag_id FOREIGN KEY (tag_id) REFERENCES collection(id);

insert into evolutions (name, description) values('165.sql', 'add tag_id to raw_keep');

# --- !Downs