# SHOEBOX

# --- !Ups

CREATE TABLE keep_to_email (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state varchar(20) NOT NULL,
  keep_id BIGINT(20) NOT NULL,
  email_address VARCHAR(512) NOT NULL,
  email_address_hash VARCHAR(26) NOT NULL, -- see email_address.hash (evolution 371.sql)
  added_by bigint(20) DEFAULT NULL,
  added_at DATETIME NOT NULL,
  uri_id BIGINT(20) NOT NULL,
  last_activity_at DATETIME NOT NULL,

  PRIMARY KEY(id),
  UNIQUE INDEX keep_to_email_u_keep_id_email_address (keep_id, email_address_hash),
  CONSTRAINT keep_to_email_f_keep_id FOREIGN KEY (keep_id) REFERENCES bookmark(id),
  CONSTRAINT keep_to_email_f_added_by FOREIGN KEY (added_by) REFERENCES user(id)
);

insert into evolutions (name, description) values(
  '435.sql',
  'Add keep_to_email'
);

# --- !Downs
