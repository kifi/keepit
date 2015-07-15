# SHOEBOX

# --- !Ups

CREATE TABLE organization_experiment (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    organization_id bigint(20) NOT NULL,
    experiment_type varchar(20) NOT NULL,
    
    PRIMARY KEY (id),
    UNIQUE INDEX organization_experiment_u_organization_id_experiment_type (organization_id, experiment_type),
    CONSTRAINT organization_experiment_organization_id FOREIGN KEY (organization_id) REFERENCES organization(id)
);

insert into evolutions (name, description) values('356.sql', 'adding org experiment');

# --- !Downs
