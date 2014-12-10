# SHOEBOX

# --- !Ups

CREATE TABLE library_image (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  image_path varchar(64) NOT NULL,
  image_format varchar(16) NOT NULL,
  width smallint(6) unsigned NOT NULL,
  height smallint(6) unsigned NOT NULL,
  center_x smallint(6) unsigned NOT NULL,
  center_y smallint(6)  unsigned NOT NULL,
  selected_width smallint(6) unsigned NOT NULL,
  selected_height smallint(6) unsigned NOT NULL,
  source varchar(32) NOT NULL,
  source_file_hash varchar(32) DEFAULT NULL,
  source_image_url varchar(3072) DEFAULT NULL,
  is_original tinyint(1) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY library_image_u_source_file_hash_size_library_id (source_file_hash,width,height,library_id),
  KEY library_image_f_library_id (library_id)
);

CREATE TABLE library_image_request (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  token varchar(32) NOT NULL,
  failure_code varchar(32) DEFAULT NULL,
  failure_reason text,
  success_hash varchar(32) DEFAULT NULL,
  source varchar(32) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY library_image_request_u_token (token),
  KEY library_image_request_f_library_id (library_id)
);

insert into evolutions (name, description) values('271.sql', 'create new tables library_image and library_image_request');

# --- !Downs
