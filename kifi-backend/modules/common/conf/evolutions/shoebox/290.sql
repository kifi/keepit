# SHOEBOX

# --- !Ups

create table activity_email(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  user_id long NOT NULL,

  content_other_followed_libraries VARCHAR(255) NULL,
  content_user_followed_libraries VARCHAR(255) NULL,
  content_library_recommendations VARCHAR(255) NULL,

  PRIMARY KEY (id),
  UNIQUE KEY activity_email_i_id (id),
  INDEX activity_email_i_user_id (user_id),
  FOREIGN KEY activity_email_fk_user_id REFERENCES `user`(id)
);

insert into evolutions (name, description) values('290.sql', 'adding shoebox.activity_email table');

# --- !Downs
