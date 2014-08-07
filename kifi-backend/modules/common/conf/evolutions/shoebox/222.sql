# CORTEX

# --- !Ups

CREATE TABLE lda_info(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  version tinyint unsigned NOT NULL,
  dimension smallint unsigned NOT NULL,
  topic_id smallint unsigned NOT NULL,
  topic_name varchar(64) NOT NULL,
  is_active bool NOT NULL,
  is_nameable bool NOT NULL,
  num_docs int unsigned NOT NULL,

  PRIMARY Key (id),
  INDEX lda_info_i_version_topic_id (version, topic_id)
);

insert into evolutions (name, description) values('222.sql', 'adding lda_info');

# --- !Downs
