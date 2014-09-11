# CORTEX

# --- !Ups

create table library_lda_topic (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  library_id bigint(20) NOT NULL,
  version tinyint(3) unsigned NOT NULL,
  num_of_evidence int unsigned NOT NULL,
  topic blob DEFAULT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  Unique Index library_lda_topic_i_library_version (library_id, version)

);

insert into evolutions (name, description) values('243.sql', 'adding library lda topic');

# --- !Downs
