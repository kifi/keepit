# SHOEBOX

# --- !Ups

CREATE TABLE keep_to_library (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  keep_id bigint(20) NOT NULL,
  library_id bigint(20) NOT NULL,
  added_by bigint(20) NOT NULL,
  added_at DATETIME NOT NULL,
  uri_id BIGINT(20) NOT NULL,
  is_primary TINYINT(1) DEFAULT true,
  visibility VARCHAR(32) NOT NULL,
  organization_id BIGINT(20) DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE INDEX `keep_to_library_u_keep_id_library_id` (`keep_id`, `library_id`),
  UNIQUE INDEX `keep_to_library_u_library_id_is_primary_uri_id` (`library_id`, `is_primary`, `uri_id`),
  CONSTRAINT `keep_to_library_f_keep_id_uri_id` FOREIGN KEY (`keep_id`, `uri_id`) REFERENCES bookmark(`id`, `uri_id`),
  CONSTRAINT `keep_to_library_f_library_id_visibility_organization_id` FOREIGN KEY (`library_id`, `visibility`, `organization_id`) REFERENCES library(`id`, `visibility`, `organization_id`),
  CONSTRAINT `keep_to_library_f_added_by` FOREIGN KEY (`added_by`) REFERENCES user(`id`),
  CONSTRAINT `keep_to_library_f_uri_id` FOREIGN KEY (`uri_id`) REFERENCES normalized_uri(`id`),
  CONSTRAINT `keep_to_library_f_organization_id` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`)
);


insert into evolutions(name, description) values('362.sql', 'create keep-to-library relation table');

# --- !Downs
