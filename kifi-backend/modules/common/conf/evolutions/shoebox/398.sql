# SHOEBOX

# --- !Ups

ALTER TABLE organization DROP COLUMN base_permissions;
ALTER TABLE organization_membership DROP COLUMN permissions;
ALTER TABLE paid_plan DROP COLUMN features;
ALTER TABLE paid_account DROP COLUMN feature_settings;

ALTER TABLE paid_plan ADD COLUMN editable_features text NOT NULL;
ALTER TABLE paid_plan ADD COLUMN default_settings text NOT NULL;

CREATE TABLE organization_configuration (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    organization_id bigint(2) NOT NULL,
    settings text NOT NULL,

    PRIMARY KEY(id),
    UNIQUE KEY `organization_configuration_u_organization_id` (`organization_id`),
    CONSTRAINT `organization_configuration_f_organization_id` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`)
);

insert into evolutions(name, description) values('398.sql', 'refactor org permissions: drop org_membership.permissions, drop org.base_permissions, paid_plan.features -> {paid_plan.editable_features, paid_plan.default_settings}, paid_account.feature_settings -> org_config.settings');

# --- !Downs
