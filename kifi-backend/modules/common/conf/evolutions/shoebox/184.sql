# CORTEX

# --- !Ups

CREATE TABLE feature_commit_info(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  model_name varchar(64) NOT NULL,
  model_version tinyint(3) unsigned NOT NULL,
  seq bigint(20) NOT NULL,

  PRIMARY KEY (id)
);

insert into evolutions (name, description) values('184.sql', 'add feature_commit_info table');

# --- !Downs

