!#/bin/bash

# le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 init

# le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 register --hostname=heimdal-demand-1 --name=heimdal-demand-1 --force

#le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/syslog
#le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/lastlog

le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/heimdal/log/db.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/heimdal/log/app.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/heimdal/log/access.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/heimdal/log/cache.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/heimdal/log/heimdal.out
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/nginx/access.log --name=nginx_access
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/nginx/error.log --name=nginx_error

sudo /etc/init.d/logentries restart


