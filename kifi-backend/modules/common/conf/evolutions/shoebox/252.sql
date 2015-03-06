# SHOEBOX

# --- !Ups

CREATE TABLE IF NOT EXISTS keep_image_request (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  token varchar(32) NOT NULL,
  failure_code varchar(32) NULL,
  failure_reason text NULL,
  success_hash varchar(32) NULL,
  image_uri varchar(3072) NULL,
  source varchar(32) NOT NULL,

  PRIMARY KEY (id),
  INDEX keep_image_request_f_keep_id (keep_id),
  UNIQUE INDEX keep_image_request_u_token (token)
);
insert into evolutions (name, description) values('252.sql', 'create keep_image_request table');

# --- !Downs
