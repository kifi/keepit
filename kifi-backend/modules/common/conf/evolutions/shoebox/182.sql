# CORTEX

# --- !Ups

CREATE TABLE uri_lda_topic(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  uri_id bigint(20) NOT NULL,
  uri_seq bigint(20) DEFAULT NOT NULL,
  version tinyint(3) unsigned NOT NULL,
  first_topic smallint(6) unsigned,
  second_topic smallint(6) unsigned,
  third_topic smallint(6) unsigned,
  sparse_feature varchar(512) NOT NULL,
  feature blob NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  INDEX uri_id_version_index (uri_id, version),
  INDEX uri_seq_version_index (uri_seq, version),
  INDEX first_topic_version_index (first_topic, version),
  INDEX second_topic_version_index (second_topic, version),
  INDEX third_topic_version_index (third_topic, version)

);

insert into evolutions (name, description) values('182.sql', 'adding uri_lda_topic');

# --- !Downs
