# HEIMDAL 

# --- !Ups

CREATE TABLE re_keep (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,

  keeper_id bigint(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,

  src_user_id bigint(20) NOT NULL,
  src_keep_id bigint(20) NOT NULL,
  attr_factor int NOT NULL DEFAULT 1,

  PRIMARY KEY (id),

  INDEX re_keep_keeper_id (keeper_id),
  INDEX re_keep_src_user_id (src_user_id),
  INDEX re_keep_uri_id (uri_id),
  INDEX re_keep_keep_id (keep_id),
  INDEX re_keep_src_keep_id (src_keep_id)
);

insert into evolutions (name, description) values('216.sql', 'add re_keep table');

# --- !Downs
