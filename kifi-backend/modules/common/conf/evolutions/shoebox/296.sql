# SHOEBOX

# --- !Ups
CREATE TABLE library_suggested_search(
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  library_id bigint(20) NOT NULL,
  term varchar(128) NOT NULL,
  weight real NOT NULL,
  state varchar(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE INDEX library_suggested_search_i_library_id_term (library_id, term),
  INDEX library_suggested_search_i_library_id_state (library_id, state)
);

insert into evolutions (name, description) values('296.sql', 'creates library_suggested_search');

# --- !Downs
