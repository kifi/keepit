# SHOEBOX

# --- !Ups

CREATE INDEX image_info_i_uri_id ON image_info (uri_id);

insert into evolutions (name, description) values('168.sql', 'adding uri_id index to image_info');

# --- !Downs
