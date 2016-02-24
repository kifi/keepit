# SHOEBOX

# --- !Ups

create index user_to_domain_i_domain_id_kind_updated_at on user_to_domain(domain_id, kind, updated_at);

insert into evolutions(name, description) values('430.sql', 'add index user_to_domain_i_domain_id_kind_updated_at');

# --- !Downs
