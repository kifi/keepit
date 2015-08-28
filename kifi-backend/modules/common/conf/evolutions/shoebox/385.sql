# SHOEBOX

# --- !Ups
INSERT INTO `paid_plan` (`id`, `created_at`, `updated_at`, `state`, `name`, `billing_cycle`, `price_per_user_per_cycle`, `kind`)
VALUES
  (1, '2015-08-19 21:50:48', '2015-08-19 21:50:48', 'active', 'Test', 1, 0, 'normal');
insert into evolutions (name, description) values('385.sql', 'adding default paid plan');

# --- !Downs
