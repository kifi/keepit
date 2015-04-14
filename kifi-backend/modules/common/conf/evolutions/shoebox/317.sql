=======
# CURATOR

# --- !Ups

create table uri_reco_feedback(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  feedback varchar(32) NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
);

insert into evolutions (name, description) values('317.sql', 'create uri_reco_feedback table');

# --- !Downs
