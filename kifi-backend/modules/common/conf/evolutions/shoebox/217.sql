# HEIMDAL

# --- !Ups

CREATE TABLE user_keep_info (

  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,

  user_id bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  self_clicks int NOT NULL,
  other_clicks int NOT NULL,

  rekeep_count int DEFAULT 0,
  rekeep_total_count int DEFAULT 0,
  rekeep_degree int DEFAULT 0,

  PRIMARY KEY (id),

  UNIQUE INDEX user_keep_info_index (user_id,uri_id),
  INDEX user_keep_info_uri_id (uri_id),
  INDEX user_keep_info_rekeep_direct_count (rekeep_count),
  INDEX user_keep_info_rekeep_total_count (rekeep_total_count)
);

insert into evolutions(name, description) values('217.sql', 'add user_keep_info table');

# --- !Downs
