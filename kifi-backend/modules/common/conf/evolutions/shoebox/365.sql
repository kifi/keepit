# SHOEBOX

# --- !Ups

ALTER TABLE organization_domain_ownership DROP CONSTRAINT unique_organization_id_and_domain_id;
ALTER TABLE organization_domain_ownership DROP COLUMN domain_id;
ALTER TABLE organization_domain_ownership ADD domain_hostname VARCHAR(128) NOT NULL;

ALTER TABLE organization_domain_ownership ADD CONSTRAINT organization_domain_ownership_domain_hostname
  FOREIGN KEY(domain_hostname) REFERENCES domain(hostname);


ALTER TABLE organization_domain_ownership ADD CONSTRAINT unique_domain_hostname UNIQUE(domain_hostname);

insert into evolutions (name, description) values('365.sql', 'Make organization_domain_ownership reference domain hashes');

# --- !Downs
WithFilter
