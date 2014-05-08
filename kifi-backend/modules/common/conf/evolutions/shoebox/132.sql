# SHOEBOX

# --- !Ups

CREATE TABLE raw_keep (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  user_id bigint(20) NOT NULL,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  url varchar(2048) NOT NULL,
  title varchar(2048) DEFAULT NULL,
  is_private tinyint(1) DEFAULT NULL,
  import_id varchar(36) DEFAULT NULL,
  source varchar(256) NOT NULL,
  installation_id varchar(36) DEFAULT NULL,
  original_json TEXT NULL,
  state varchar(20) NOT NULL,
  PRIMARY KEY (id),
  KEY raw_keep_i_state (state),
  CONSTRAINT raw_keep_f_user FOREIGN KEY (user_id) REFERENCES user (id)
);

insert into evolutions (name, description) values('132.sql', 'creating table raw_keep');

# --- !Downs
