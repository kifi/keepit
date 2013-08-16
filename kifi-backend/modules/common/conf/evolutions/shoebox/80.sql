# SHOEBOX

# --- !Ups

CREATE TABLE IF NOT EXISTS uri_normalization_rule (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  prep_url_hash varchar(26) NOT NULL,
  prep_url varchar(3072) NOT NULL,
  mapped_url varchar(3072) NOT NULL,
  state varchar(20) NOT NULL,

  Key (prep_url_hash),
  PRIMARY KEY (id),

  CONSTRAINT uri_normalization_rule_prep_url_hash UNIQUE (prep_url_hash)
);


CREATE TABLE failed_content_check (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  url1_hash varchar(26) NOT NULL,
  url2_hash varchar(26) NOT NULL,
  url1 varchar(3072) NOT NULL,
  url2 varchar(3072) NOT NULL,
  state varchar(20) NOT NULL,
  counts integer NOT NULL,
  last_content_check datetime NOT NULL,

  unique Key (url1_hash, url2_hash),
  PRIMARY KEY (id)

);

CREATE TABLE changed_uri (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  old_uri_id bigint(20) NOT NULL,
  new_uri_id bigint(20) NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,

  PRIMARY KEY (id)
);


insert into evolutions (name, description) values('80.sql', 'adding uri_normalization_rule and failed_uri_normalization');
# --- !Downs
