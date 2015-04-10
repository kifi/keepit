# CURATOR

# --- !Ups

create table uri_reco_feedback(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  uri_id bigint(20) NOT NULL,
  viewed boolean DEFAULT NULL,
  clicked boolean DEFAULT NULL,
  kept boolean DEFAULT NULL,
  liked boolean DEFAULT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  Unique index uri_reco_feedback_i_user_uri (user_id, uri_id)

);

insert into evolutions (name, description) values('316.sql', 'create uri_reco_feedback table');

# --- !Downs
