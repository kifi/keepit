# SHOEBOX

# --- !Ups

CREATE TABLE page_info (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  description varchar(1024),
  safe boolean,
  favicon_url varchar(1024),
  image_avail boolean,
  screenshot_avail boolean,

  PRIMARY KEY (id),
  UNIQUE INDEX page_info_uri_id (uri_id)
);

-- MySQL:
-- CREATE TABLE page_info_sequence (id INT NOT NULL);
-- INSERT INTO page_info_sequence VALUES (0);


CREATE SEQUENCE page_info_sequence;
CREATE INDEX page_info_seq_index on page_info(seq);

insert into evolutions (name, description) values('145.sql', 'adding page_info');

# --- !Downs
