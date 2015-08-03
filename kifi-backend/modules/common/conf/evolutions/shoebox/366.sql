# SHOEBOX

# --- !Ups

--- MySQL:
---   ALTER TABLE domain MODIFY hostname varchar(256) CHARACTER SET ascii COLLATE ascii_general_ci;
---   ALTER TABLE organization_domain_ownership MODIFY domain_hostname varchar(256) CHARACTER SET ascii COLLATE ascii_general_ci;
---   ALTER TABLE domain DROP hash;
---

ALTER TABLE domain ALTER COLUMN hostname varchar_ignorecase(256) NOT NULL;

insert into evolutions (name, description) values('366.sql', 'change charset for domain.hostname');

# --- !Downs
