# SHOEBOX

# --- !Ups

CREATE INDEX user_ip_addresses_i_seq ON user_ip_addresses(seq);

CREATE INDEX organization_membership_i_seq ON organization_membership(seq);

ALTER TABLE domain ADD hash VARCHAR(26) DEFAULT NULL;

insert into evolutions (name, description) values('361.sql', 'indices on domain, user_ip_addresses, and org_membership');

# --- !Downs
