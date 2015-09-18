# About

Ansible is used to create, provision, and maintain EC2 instances.

# Installation (OS X)

1. Install ansible: `brew install ansible`
2. Install dependent roles: `ansible-galaxy install -r requirements.txt -p roles/`

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

The `ec2-local.yml` playbook is used when ansible is used to provision localhost.
It is referenced by [scripts/ec2-self-provision](./scripts/ec2-self-provision), 
which is called by the [ec2-user-data](./ec2-user-data) script.

For safetey, if non-trivial changes are made, it's advised to use the `--limit`
option against a particular host or group before running the updates against
all instances.

Before you can run any of the `ansible-playbook` commands below, you must export your AWS access and secret keys as environment variables, because `ec2.py` pulls a list of nodes to run ansible against using the EC2 API.

```sh
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
```

## FAQ

### Where do I run ansible from?

Files from the `provisioning` directory of this machine are synced on arthur. Since 
it's possible to edit files on arthur directly, it's easy to forget to update them 
on git. Thus, it's important to update them in Git as well to avoid changes from 
from being overwritten.

### How to launch new instances with ansible?

1. edit the `ec2.instances` section in `vars/ec2.yml`
2. run the `production-launch-ec2.yml` playbook:
    
    ```
    ansible-playbook -i ec2.py production-launch-ec2.yml
    ```
3. on launch, the instance will automatically download the latest ansible 
playbook and supporting files from S3 and update itself. The ansible output is on each EC2 instance under `/var/log/cloud-init-output.log`. This is different from traditional ansible playbooks where commands are usually run over ssh. The reason for this is to be consistent between on-demand instances and spot instances that launch and restart asynchronously.

### How to update existing instances with ansible?

1. edit the necessary files, probably under `./{files,templates}` or `roles/kifi.common/{files,templates}`
2. run the `production-update.yml` playbook:

   ```
   # to update only shoebox
   ansible-playbook -i ec2.py production-update.yml  --limit tag_Service_shoebox
   
   # to update only a particular node
   ansible-playbook -i ec2.py production-update.yml --limit ec2-54-151-31-86.us-west-1.compute.amazonaws.com
   ```
   
   [Read more about patterns you can use](http://docs.ansible.com/intro_patterns.html) with `--limit` .


### How to launch spot instances?

Even though you can launch spot instances using ansible, there are a few things ansible does not support out of the box, like setting persistent spot requests and setting tags on the spot request. Because of this, it's recommended to use the EC2 console.

**You will need to set the IAM role and the User data fields**. The [IAM role](https://console.aws.amazon.com/iam/home?region=us-west-1#roles/kifi-service) allows the instance to make API calls to AWS w/o embedding access/secret keys. For the *User data* field, attach the [ec2-user-data](./ec2-user-data) file. This is a script that is run on the instance as it launches.  

![image](https://cloud.githubusercontent.com/assets/182629/9639483/3e4ba1e0-517a-11e5-9e4e-7e7cd1220359.png)

### How do I update the ansible files that new demand and spot instances download?

**Use with caution** and make sure you have the latest versions of files in the `provisioning` directory. This script will tars your local `provisioning` directory and upload it to S3. The tarballs are not currently versioned on S3 (TODO).

```
./scripts/upload-tarball-to-s3
```

### Known Warts

* Running the `production-launch-ec2.yml` playbook can hang if the instances it
  is trying to launch are have already been created and are stopped on EC2.
* Launching a spot request via the `production-launch-ec2.yml` by setting the 
  `ec2.instances[].spot_price` does not add tags to the spot request but will wait for 
  the instance to launch and add the tags to the instance
* Ansible < 2.0 does not support launching persistent spot request

## TODOs

* Version the ansible tarballs on S3
* Automatically upload new tarballs to S3 with Jenkins
* Add safe-guards and better automation to keep the ansible files on arthur up-to-date

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
