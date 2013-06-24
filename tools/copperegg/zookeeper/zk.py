import pycurl
import time
import os
import socket

# to install run
# crontab -e
# and add
# */1 * * * * /usr/bin/flock -n /var/lock/zookeeper_cron /usr/bin/python /home/fortytwo/copperegg/scripts/zk.py
# to check edit run
# crontab -l
# make sure crond is running
# /sbin/service crond status

zkStatStr = os.popen("/etc/init.d/zookeeper status").read()
zkStatus = 0
if zkStatStr.find("zookeeper is running") >= 0:
  zkStatus = 1

with open ("/opt/zookeeper/data/myid", "r") as myfile:
  zkId = myfile.read().replace('\n', '')
URL = "http://api.copperegg.com/v2/revealmetrics/samples/zk.json"
userPasswd = "dTZpn2grdgY9qATB:U"
postdata = {"identifier": "zk" + zkId, "timestamp": int(time.time()), "values": {"alive": zkStatus}}

print postdata

c = pycurl.Curl()
c.setopt(c.URL, URL)
c.setopt(c.POST, 1)
c.setopt(c.POSTFIELDS, str(postdata))
c.setopt(c.HTTPHEADER, ["Content-Type: application/json"])
c.setopt(c.HTTPAUTH, c.HTTPAUTH_BASIC)
c.setopt(c.USERPWD, userPasswd)
c.setopt(c.VERBOSE, 1)

try:
    c.perform()
except:
    print "Error performing curl perform"
zkMonitorLog = open('/home/fortytwo/copperegg/scripts/zkMonitor.log', 'a')
zkMonitorLog.write("\n" + str(postdata))
zkMonitorLog.write("\n" + zkStatStr)

print c.getinfo(pycurl.HTTP_CODE), c.getinfo(pycurl.EFFECTIVE_URL)
c.close()
