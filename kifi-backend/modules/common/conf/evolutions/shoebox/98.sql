# SHOEBOX

# --- !Ups

CREATE TABLE renormalized_url (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  url_id bigint(20) NOT NULL,
  new_uri_id bigint(20) NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,

  PRIMARY KEY (id)
);

CREATE SEQUENCE renormalized_url_sequence;
CREATE INDEX renormalized_url_seq_index on renormalized_url(seq);

insert into evolutions (name, description) values('98.sql', 'adding renormalized_url');


# --- !Downs