# HEIMDAL

# --- !Ups

CREATE TABLE keep_discovery (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,

  hit_uuid varchar(36) NOT NULL,
  num_keepers int NOT NULL DEFAULT 1,
  keeper_id bigint(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,

  origin varchar(256) DEFAULT NULL,

  PRIMARY KEY (id),

  INDEX keep_discovery_keeper_id (keeper_id),
  INDEX keep_discovery_keep_id (keep_id),
  INDEX keep_discovery_uri_id (uri_id),
  INDEX keep_discovery_hit_uuid (hit_uuid)
);

insert into evolutions (name, description) values('215.sql', 'adding keep_discovery table');

# --- !Downs
