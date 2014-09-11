# CORTEX

# --- !Ups

create table cortex_library(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  library_id bigint(20) NOT NULL,
  owner_id bigint(20) NOT NULL,
  kind varchar(64) NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,

  Primary KEY (id),
  INDEX cortex_library_i_library_id (library_id),
  INDEX cortex_library_i_owner (owner_id),
  INDEX cortex_library_i_seq (seq)
);

create table cortex_library_membership(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  membership_id bigint(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  user_id bigint(20) NOT NULL,
  access varchar(20) NOT NULL,
  member_since datetime NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) NOT NULL,

  Primary KEY (id),
  INDEX cortex_library_membership_i_user (user_id),
  INDEX cortex_library_membership_i_library (library_id),
  INDEX cortex_library_membership_i_seq (seq)
);

insert into evolutions (name, description) values('242.sql', 'adding cortex_library and cortex_library_membership');

# --- !Downs
