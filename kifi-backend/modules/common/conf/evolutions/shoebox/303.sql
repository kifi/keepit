# SHOEBOX

# --- !Ups

create table keep_source_attribution(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  attr_type varchar(256) NOT NULL,
  attr_json text NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id)

);

insert into evolutions (name, description) values('303.sql', 'create table keep_source_attribution');


# --- !Downs
