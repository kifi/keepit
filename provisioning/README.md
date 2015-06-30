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

The `production-launch-ec2.yml` playbook will use the EC2 API to launch instances before
they are provisioned. As long as the `ec2` task has an `id` field, the instance
creation will be idempotent (instances with the given `id` will only be created
once).

The `production-update.yml` playbook will provision existing EC2 instances.
This playbook is separate from `production-launch-ec2.yml` because it will be
common to want to launch new instances and then run ansible *only* against
those instances. The `--limit` option can be used to tell ansible which
instances to run on, but it will _not_ work against instances that were are
launched dynamically by ansible during the same playbook run. Also, if you only
care to update existing instances that you know are running, there's no use in
running the tasks to launch new ones, as they will just slow the script down.

For safetey, if non-trivial changes are made, it's advised to use the `--limit`
option against a particular host or group before running the updates against
all instances.

### Examples

Below are examples of running playbooks to launch and provision instances.

```sh
# setting these environment variables is required
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."

ansible-playbook -i ec2.py production-launch-ec2.yml
ansible-playbook -i ec2.py production-update.yml
```

To run against particular host(s) you can use the `--limit` option, which takes
a [pattern](http://docs.ansible.com/intro_patterns.html) to describe the
instances to run ansible on.

```sh
# example using an ansible-recognized host
ansible-playbook -i ec2.py production-update.yml --limit ec2-54-151-31-86.us-west-1.compute.amazonaws.com

# example using a dynamic group created by ec2.py (dynamic inventory list)
ansible-playbook -i ec2.py production-update.yml --limit tag_Service_shoebox
```

### Known Warts

* Running the `production-launch-ec2.yml` playbook can hang if the instances it
  is trying to launch are have already been created and are stopped on EC2.

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
