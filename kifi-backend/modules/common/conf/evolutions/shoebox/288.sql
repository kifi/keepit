# CORTEX

# --- !Ups

create table persona_lda_feature(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  persona_id bigint(20) NOT NULL,
  version tinyint(3) unsigned NOT NULL,
  feature Blob NOT NULL,
  first_topic smallint unsigned NOT NULL,
  second_topic smallint unsigned NOT NULL,
  third_topic smallint unsigned NOT NULL,
  state varchar(20) NOT NULL

  PRIMARY KEY (id),
  unique key persona_lda_feature_i_version_persona (version, persona_id)
);

insert into evolutions (name, description) values('288.sql', 'adding persona_lda_feature table');

# --- !Downs
