# SHOEBOX

# --- !Ups

ALTER TABLE paid_plan ADD COLUMN features text NOT NULL;

ALTER TABLE paid_account ADD COLUMN feature_settings text NOT NULL;

INSERT INTO `paid_plan` (`id`, `created_at`, `updated_at`, `state`, `name`, `billing_cycle`, `price_per_user_per_cycle`, `kind`, `features`)
VALUES
  (1, '2015-08-19 21:50:48', '2015-08-19 21:50:48', 'active', 'Test', 1, 0, 'normal', '[]');

insert into evolutions(name, description) values('397.sql', 'add features to paid_plan, add feature_settings to paid_account');

# --- !Downs
