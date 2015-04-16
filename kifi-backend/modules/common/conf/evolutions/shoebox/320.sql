# CURATOR

# --- !Ups

create table user_reco_feedback_counter(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  vote_ups blob NOT NULL,
  vote_downs blob NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  index user_reco_feedback_counter_i_user_id (user_id)
);

insert into evolutions (name, description) values('320.sql', 'create user_reco_feedback_counter table');

# --- !Downs
