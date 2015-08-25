# SHOEBOX

# --- !Ups

CREATE TABLE keep_to_user (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  user_id bigint(20) NOT NULL,
  added_by bigint(20) NOT NULL,
  added_at DATETIME NOT NULL,
  uri_id BIGINT(20) NOT NULL,

  PRIMARY KEY(id),
  UNIQUE INDEX `keep_to_user_u_keep_id_user_id` (`keep_id`, `user_id`),
  INDEX `keep_to_user_i_user_id_uri_id` (`user_id`, `uri_id`),
  CONSTRAINT `keep_to_user_f_keep_id_uri_id` FOREIGN KEY (`keep_id`, `uri_id`) REFERENCES bookmark(`id`, `uri_id`),
  CONSTRAINT `keep_to_user_f_user_id` FOREIGN KEY (`user_id`) REFERENCES user(`id`),
  CONSTRAINT `keep_to_user_f_added_by` FOREIGN KEY (`added_by`) REFERENCES user(`id`)
);


insert into evolutions (name, description) values('379.sql', 'create keep-to-user table');

# --- !Downs
