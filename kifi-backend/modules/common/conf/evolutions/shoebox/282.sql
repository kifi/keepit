# CORTEX

# --- !Ups

create table lda_related_library(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  version tinyint(3) unsigned NOT NULL,
  source_id bigint(20) NOT NULL,
  dest_id bigint(20) NOT NULL,
  weight real NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  unique index lda_related_library_i_version_source (version, source_Id, dest_id)
);

insert into evolutions (name, description) values('282.sql', 'adding lda_related_library');

# --- !Downs
