#HEIMDAL

# --- !Ups

create table library_view(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  owner_id bigint(20) NOT NULL,
  viewer_id bigint(20) NULL,
  library_id bigint(20) NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  index library_view_i_owner_time (owner_id, created_at)
)

insert into evolutions (name, description) values('328.sql', 'create library_view table');

# --- !Downs