# SHOEBOX

# --- !Ups
ALTER TABLE page_info drop COLUMN seq;

insert into evolutions (name, description) values('297.sql', 'drop page info sequence number');

# --- !Downs