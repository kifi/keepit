# ROVER

# --- !Ups

ALTER TABLE article_info add column image_processing_requested_at datetime NULL;
CREATE INDEX article_info_i_image_processing_requested_at ON article_info(image_processing_requested_at);
CREATE INDEX article_info_i_last_image_processing_at ON article_info(last_image_processing_at);

insert into evolutions (name, description) values('324.sql', 'add image_processing_requested_at column, article_info_i_image_processing_requested_at index, article_info_i_last_image_processing_at index to article_info table');

# --- !Downs
