# SHOEBOX

# --- !Ups

create table library_subscription (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  name varchar(32) NOT NULL,
  trigger varchar(32) NOT NULL,
  info varchar(2048) NOT NULL,

  PRIMARY KEY (id),
  INDEX library_subscription_i_library_id (library_id),
  UNIQUE INDEX(library_id, name)
);

insert into evolutions (name, description) values('338.sql', 'adding library subscription');

# --- !Downs
