# CORTEX

# --- !Ups

CREATE TABLE user_lda_stats(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  version tinyint(3) unsigned NOT NULL,
  num_of_evidence int unsigned NOT NULL,
  user_topic_mean blob DEFAULT NULL,
  user_topic_var blob DEFAULT NULL,
  state varchar(20) NOT NULL,

  PRIMARY KEY (id),
  INDEX user_lda_stats_user_version (user_id, version)
);

insert into evolutions (name, description) values('215.sql', 'adding user_lda_stats');

# --- !Downs
