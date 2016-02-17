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
    organization_id bigint(20) NOT NULL,
    settings text NOT NULL,

    PRIMARY KEY(id),
    UNIQUE KEY `organization_configuration_u_organization_id` (`organization_id`),
    CONSTRAINT `organization_configuration_f_organization_id` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`)
);

INSERT INTO `paid_plan` (`id`, `created_at`, `updated_at`, `state`, `name`, `billing_cycle`, `price_per_user_per_cycle`, `kind`, `editable_features`, `default_settings`)
VALUES
  (1, '2015-08-19 21:50:48', '2015-08-19 21:50:48', 'active', 'Test', 1, 0, 'normal',
  '["force_edit_libraries","invite_members","group_messaging","edit_organization","view_organization","remove_libraries","create_slack_integration","export_keeps","publish_libraries","view_members", "view_settings", "join_by_verifying", "slack_ingestion_reaction", "slack_digest_notif"]',
  '{"publish_libraries":"members","group_messaging":"members","force_edit_libraries":"disabled","export_keeps":"admins","view_members":"anyone","create_slack_integration":"disabled","edit_organization":"admins",
  "remove_libraries":"members","invite_members":"members","view_organization":"anyone", "view_settings":"members", "join_by_verifying":"nonmembers", "slack_ingestion_reaction": "disabled", "slack_digest_notif": "enabled", "slack_ingestion_domain_blacklist": [] }'
  );

insert into evolutions(name, description) values('398.sql', 'refactor org permissions: drop org_membership.permissions, drop org.base_permissions, paid_plan.features -> {paid_plan.editable_features, paid_plan.default_settings}, paid_account.feature_settings -> org_config.settings');

# --- !Downs
