# SHOEBOX

# --- !Ups

-- mysql:
--    ALTER TABLE library DROP KEY `library_owner_id_slug`;
--    CREATE UNIQUE INDEX library_owner_id_org_id_slug on library(owner_id, organization_id, slug);

ALTER TABLE library DROP CONSTRAINT IF EXISTS library_owner_id_slug;
CREATE UNIQUE INDEX library_owner_id_org_id_slug on library(owner_id, organization_id, slug);

insert into evolutions (name, description) values('394.sql', 'Changing constraints for library to account for orgs with lib slugs');


# --- !Downs
