# SHOEBOX

# --- !Ups

CREATE TABLE export_request (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state varchar(20) NOT NULL,
  user_id BIGINT(20) NOT NULL,
  started_processing_at DATETIME DEFAULT NULL,
  finished_processing_at DATETIME DEFAULT NULL,
  failure_message TEXT DEFAULT NULL,
  upload_location VARCHAR(1024) DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE INDEX export_request_u_user_id (user_id),
  CONSTRAINT export_request_f_user_id FOREIGN KEY (user_id) REFERENCES user(id)
);

insert into evolutions (name, description) values(
  '448.sql',
  'Introduce export_request'
);

# --- !Downs
