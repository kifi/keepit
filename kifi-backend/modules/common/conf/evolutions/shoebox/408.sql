# SHOEBOX

# --- !Ups

ALTER TABLE article_info MODIFY COLUMN uri_id bigint(20) DEFAULT NULL;
ALTER TABLE article_image MODIFY COLUMN uri_id bigint(20) DEFAULT NULL;
insert into evolutions(name, description) values('408.sql', 'make article_info.uri_id and article_image.uri_id nullable');

# --- !Downs
