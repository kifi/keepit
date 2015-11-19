# SHOEBOX

# --- !Ups

ALTER TABLE organization_domain_ownership DROP CONSTRAINT IF EXISTS unique_domain_hostname;
CREATE UNIQUE INDEX organization_domain_ownership_u_organization_id_domain_hostname on organization_domain_ownership(organization_id, domain_hostname);
insert into evolutions(name, description) values('411.sql', 'drop unique_domain_hostname, add organization_domain_ownership_u_organization_id_domain_hostname');

# --- !Downs
