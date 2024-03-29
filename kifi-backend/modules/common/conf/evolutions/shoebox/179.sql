# CORTEX

# --- !Ups

CREATE TABLE cortex_uri(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  uri_id bigint(20) NOT NULL,

  state varchar(20) NOT NULL,
  seq bigint(20) DEFAULT NULL,

  PRIMARY KEY (id),
  INDEX cortex_uri_id (uri_id),
  INDEX cortex_uri_seq (seq)
);

CREATE TABLE cortex_keep(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  kept_at datetime NOT NULL,
  keep_id bigint(20) NOT NULL,
  user_id bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  is_private tinyint(1) DEFAULT NULL,
  state varchar(20) NOT NULL,
  source varchar(256) NOT NULL,
  seq bigint(20) DEFAULT NULL,

  PRIMARY KEY (id),

  INDEX cortex_keep_id (keep_id),
  INDEX keep_user_id (user_id),
  INDEX keep_seq (seq)
);

insert into evolutions (name, description) values('179.sql', 'adding cortex_uri and cortex_keep');

# --- !Downs
