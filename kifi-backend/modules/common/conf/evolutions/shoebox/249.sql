# SHOEBOX

# --- !Ups

CREATE TABLE IF NOT EXISTS keep_image (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  image_path varchar(64) NOT NULL,
  image_format varchar(16) NOT NULL,
  width smallint unsigned NOT NULL,
  height smallint unsigned NOT NULL,
  source varchar(32) NOT NULL,
  source_file_hash varchar(32),
  source_image_url varchar(3072),
  is_original boolean,

  PRIMARY KEY (id),
  INDEX keep_image_f_keep_id (keep_id),
  UNIQUE INDEX keep_image_u_source_file_hash_size_keep_id (source_file_hash, width, height, keep_id)
);
insert into evolutions (name, description) values('249.sql', 'create keep_image table');

# --- !Downs
