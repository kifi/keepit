kifi.common
===========

Common tasks to run for any Kifi service.

Requirements
------------

Any pre-requisites that may not be covered by Ansible itself or the role should be mentioned here. For instance, if the role uses the EC2 module, it may be a good idea to mention in this section that the boto package is required.

Role Variables
--------------

* The `service` variable is required to inform the system which service
  (shoebox, search, etc) this node is for.
* The `setup_nginx` variable must be True for this service to install nginx and
  copy shared nginx conf files. The assumption is made that an nginx
  configuration file exists at files/etc/nginx/conf.d/SERVICE.conf.

Example Playbook
----------------

    - hosts: tag_Service_shoebox
      roles:
         - { role: kifi.shoebox }
