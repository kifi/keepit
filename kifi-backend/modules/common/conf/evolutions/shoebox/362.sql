# SHOEBOX

# --- !Ups

CREATE TABLE keep_to_library (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  keeper_id bigint(20) NOT NULL,

  PRIMARY KEY(id),
  UNIQUE INDEX `keep_to_library_u_keep_id_library_id` (`keep_id`, `library_id`),
  CONSTRAINT `keep_to_library_f_keep_id` FOREIGN KEY (`keep_id`) REFERENCES bookmark(`id`),
  CONSTRAINT `keep_to_library_f_library_id` FOREIGN KEY (`library_id`) REFERENCES library(`id`),
  CONSTRAINT `keep_to_library_f_keeper_id` FOREIGN KEY (`keeper_id`) REFERENCES user(`id`)
);


insert into evolutions(name, description) values('362.sql', 'create keep-to-library relation table');

# --- !Downs
