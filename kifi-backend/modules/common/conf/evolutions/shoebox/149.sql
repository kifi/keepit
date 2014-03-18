# SHOEBOX

# --- !Ups

CREATE TABLE image_info (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  url varchar(2048) NOT NULL,
  name varchar(256),
  caption varchar(2048),
  width int,
  height int,
  sz int,

  PRIMARY KEY (id)
  -- UNIQUE INDEX image_info_url (url)
);

-- MySQL:
-- CREATE TABLE image_info_sequence (id INT NOT NULL);
-- INSERT INTO image_info_sequence VALUES (0);


CREATE SEQUENCE image_info_sequence;
CREATE INDEX image_info_seq_index on image_info(seq);

insert into evolutions (name, description) values('148.sql', 'adding image_info');

# --- !Downs
