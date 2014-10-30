#Shoebox

# --- !Ups

CREATE TABLE username_alias (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  last_activated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  username varchar(64) NOT NULL,
  user_id bigint(20) NOT NULL,

  PRIMARY KEY (id),
  UNIQUE INDEX username_alias_i_username (username),
  CONSTRAINT username_alias_f_user FOREIGN KEY (user_id) REFERENCES user(id)
);

CREATE TABLE library_alias (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  owner_id bigint(20) NOT NULL,
  slug varchar(50) NOT NULL,
  library_id bigint(20) NOT NULL,

  PRIMARY KEY (id),
  UNIQUE INDEX library_alias_i_owner_id_slug (owner_id, slug),
  CONSTRAINT library_alias_f_library FOREIGN KEY (library_id) REFERENCES library(id)
);

insert into evolutions (name, description) values('258.sql', 'create username_alias, library_alias tables');

# --- !Downs
