# SHOEBOX

# --- !Ups

CREATE INDEX organization_membership_i_user_id on organization_membership(user_id);
CREATE UNIQUE INDEX organization_membership_u_organization_id_user_id on organization_membership(organization_id, user_id);

insert into evolutions (name, description) values('358.sql', 'adding indices organization_membership_i_user_id, organization_membership_u_organization_id_user_id');

# --- !Downs
