# SHOEBOX

# --- !Ups

CREATE TABLE shortened_path (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state varchar(20) NOT NULL,
  path varchar(3072) NOT NULL,
  path_hash INT(10) NOT NULL,

  PRIMARY KEY(id),
  INDEX shortened_path_i_hash (path_hash)
);

insert into evolutions (name, description) values(
  '447.sql',
  'Introduce shortened_path'
);

# --- !Downs
