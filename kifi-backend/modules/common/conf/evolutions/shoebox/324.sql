# ROVER

# --- !Ups

ALTER TABLE article_info add column image_processing_requested_at datetime NULL;
CREATE INDEX article_info_i_image_processing_requested_at ON article_info(image_processing_requested_at);

insert into evolutions (name, description) values('324.sql', 'add image_processing_requested_at column to article_info table');

# --- !Downs
