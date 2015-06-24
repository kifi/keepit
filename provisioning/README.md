# About

Ansible is used to create, provision, and maintain EC2 instances.

# Installation (OS X)

1) Install ansible: `brew install ansible`
2) Install ansible-role-manager: `ansible-galaxy install -r requirements.txt -p roles/`

# Notes

The configuration for provisioning instances is in the root `provisioning/`
directory and the `provisioning/roles/kifi.*` directories.  The kifi roles are
intended to be run against new or existing instances, and can run ontop of a
clean Ubuntu AMI or the latest `ubuntu14.04-play-*-ansible` AMI. The
configuration in `vars/ec2.yml` uses the custom AMI because it speeds up
provisioning since most packages will already be installed. The files not in
the kifi role are production specific and/or must be run before any of the
roles (like setting the hostname).

# Run against production

The following ansible-playbook command uses the AWS EC2 API to build a dynamic
inventory list per the `ec2.py` and `ec2.ini` files. `ec2.ini` is the script's
configuration, which determines how instances are grouped and which ones
ansible will provision.

The `production.yml` playbook will use the EC2 API to launch instances before
they are provisioned. As long as the `ec2` task has an `id` field, the instance
creation will be idempotent (instances with the given `id` will only be created
once).

```
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
ansible-playbook -i ec2.py production.yml
```

### Known Warts

* Running the `production.yml` playbook can hang on the localhost set of tasks
  if the instances it is trying to launch are stopped on EC2.

# Running with Vagrant

You can test Ansible locally with [Vagrant](https://www.vagrantup.com). The
provided Vagrantfile and vagrant_hosts files have been preconfigured to
provision virtual machines for select services.

* Get the VM up and running via `vagrant up`
* If you want to update an existing VM, run `vagrant provision`
* ssh into the VM `vagrant ssh shoebox1`

### Vagrant Debugging

To see all variables (assumes shoebox1 is the name of the machine per Vagrantfile):

```
ansible -m setup shoebox1 -i .vagrant/provisioners/ansible/inventory/vagrant_ansible_inventory \
  -u vagrant --private-key .vagrant/machines/shoebox1/virtualbox/private_key
```
