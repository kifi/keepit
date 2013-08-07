# --- !Ups

CREATE TABLE uri_normalization_rule (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  url_hash varchar(26) NOT NULL,
  url varchar(3072) NOT NULL,
  mapped_url varchar(3072) NOT NULL,
  state varchar(20) NOT NULL,

  Key (url_hash),
  PRIMARY KEY (id),

  CONSTRAINT uri_normalization_rule_url_hash UNIQUE (url_hash)
);


CREATE TABLE failed_uri_normalization (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  url_hash varchar(26) NOT NULL,
  mapped_url_hash varchar(26) NOT NULL,
  url varchar(3072) NOT NULL,
  mapped_url varchar(3072) NOT NULL,
  state varchar(20) NOT NULL,
  failed_counts integer NOT NULL, 

  Key (url_hash, mapped_url_hash),
  PRIMARY KEY (id),

  CONSTRAINT failed_uri_normalization_url_hash UNIQUE (url_hash),
  CONSTRAINT failed_uri_normalization_mapped_url_hash UNIQUE (mapped_url_hash)
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