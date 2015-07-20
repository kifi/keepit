# SHOEBOX


# --- !Ups

-- MySQL:
-- CREATE TABLE organization_domain_ownership_sequence (id BIGINT(20) NOT NULL);
-- INSERT organization_domain_ownership_sequence SET id = 0;
-- H2:
CREATE SEQUENCE organization_domain_ownership_sequence;
----

CREATE TABLE organization_domain_ownership (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  seq bigint(20) not null,
  organization_id bigint(20) NOT NULL,
  domain_id bigint(20) NOT NULL,

  PRIMARY KEY (id),
  CONSTRAINT organization_domain_ownership_organization_id FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT organization_domain_ownership_domain_id FOREIGN KEY (domain_id) REFERENCES domain(id)
);

CREATE INDEX organization_domain_ownership_seq_index ON organization_domain_ownership(seq);
insert into evolutions (name, description) values('358.sql', 'add organization_domain_ownership and organization_domain_ownership_sequence');

# --- !Downs
