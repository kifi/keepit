# SHOEBOX

# --- !Ups

CREATE INDEX user_ip_addresses_i_seq ON user_ip_addresses(seq);

CREATE INDEX organization_membership_i_seq ON organization_membership(seq);

DROP INDEX domain_hostname_index;

CREATE INDEX domain_u_hostname ON `domain`(hostname);

insert into evolutions (name, description) values('361.sql', 'indices on domain, user_ip_addresses, and org_membership');

# --- !Downs
