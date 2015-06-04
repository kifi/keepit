# SHOEBOX

# --- !Ups

create table library_subscription(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  name varchar(32) NOT NULL,
  trigger varchar(32) NOT NULL,
  info varchar(2048) NOT NULL
)

insert into evolutions (name, description) values('335.sql', 'adding library subscription')

# --- !Downs
