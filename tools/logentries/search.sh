!#/bin/bash

le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 init

le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 register --hostname=search-spot-2 --name=search-spot-2 --force

le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/search/log/db.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/search/log/app.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/search/log/access.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/search/log/cache.log
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /home/fortytwo/run/search/log/search.out
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/syslog
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/nginx/access.log --name=nginx_access
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/nginx/error.log --name=nginx_error
le --account-key=ab262b73-aff4-4fd9-b83c-8ff7b510f7f9 follow /var/log/lastlog

sudo /etc/init.d/logentries restart


