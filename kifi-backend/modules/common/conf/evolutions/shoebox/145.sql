# SHOEBOX

# --- !Ups

CREATE TABLE page_info (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  title varchar(2048),
  description varchar(3072),
  safe boolean,
  lang varchar(256),
  favicon_url varchar(3072),
  image_info_id bigint(20),

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
