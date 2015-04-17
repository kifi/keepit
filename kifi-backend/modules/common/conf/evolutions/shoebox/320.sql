# CURATOR

# --- !Ups

create table user_reco_feedback_counter(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  up_votes blob NOT NULL,
  down_votes blob NOT NULL,
  pos_signals blob NOT NULL,
  neg_signals blob NOT NULL,
  votes_rescale_count int NOT NULL,
  signals_rescale_count int NOT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  unique index user_reco_feedback_counter_i_user_id (user_id)
);

insert into evolutions (name, description) values('320.sql', 'create user_reco_feedback_counter table');

# --- !Downs
