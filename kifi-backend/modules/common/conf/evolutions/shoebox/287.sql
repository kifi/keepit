# SHOEBOX

# --- !Ups

create table persona(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  name varchar(64) NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  constraint persona_c_name unique (name)
);

create table user_persona(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  persona_id bigint(20) NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  unique index user_persona_i_uid_pid (user_id, persona_id)

);

insert into evolutions (name, description) values('287.sql', 'adding persona and user_persona tables');

# --- !Downs
