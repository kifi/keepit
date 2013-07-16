import pycurl
import time
import os
import socket
from urllib import pathname2url
import json
import urllib2

data=urllib2.urlopen('https://www.hostedgraphite.com/a3d638f9/b4e709e5-45b0-4b89-904b-a6cc7e517e04/graphite/render/?from=-2h&target='+pathname2url('summarize(stats.timers.search.extSearch.total.mean_99,"2hour","avg")')+'&format=json')
stats = json.load(data)
current_search_avg = stats[0]['datapoints'][1][0]

URL = "http://api.copperegg.com/v2/revealmetrics/samples/search_time.json"
userPasswd = "dTZpn2grdgY9qATB:U"
postdata = {"identifier": "search", "timestamp": int(time.time()), "values": {"search_time": current_search_avg}}

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
print str(postdata)
print stats
print current_search_avg

print c.getinfo(pycurl.HTTP_CODE), c.getinfo(pycurl.EFFECTIVE_URL)
c.close()

