import pycurl
import time
import os
import socket
from urllib import pathname2url
import json
import urllib2

# see copperegg tag at
# https://app.copperegg.com/AvgyEIegYJyGjEs9#revealmetrics/metric_groups/edit_metric_group=screenshot_fail?f=healthy&dp=1hr&rmdp=1day&rmsp=1day
# alert at
# https://app.copperegg.com/AvgyEIegYJyGjEs9#issues/configure/editAlert=209848?f=healthy&dp=1hr&rmdp=1day&rmsp=1day


target=pathname2url('summarize(stats.counters.shoebox.screenshot.fetch.fails.count:sum,"6hour")')
data=urllib2.urlopen('https://www.hostedgraphite.com/a3d638f9/b4e709e5-45b0-4b89-904b-a6cc7e517e04/graphite/render/?from=-12h&target='+target+'&format=json')
stats = json.load(data)
screenshot_fail = stats[0]['datapoints'][1][0]

URL = "http://api.copperegg.com/v2/revealmetrics/samples/search_time.json"
userPasswd = "dTZpn2grdgY9qATB:U"
postdata = {"identifier": "shoebox", "timestamp": int(time.time()), "values": {"screenshot_fail": screenshot_fail}}

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
print screenshot_fail

print c.getinfo(pycurl.HTTP_CODE), c.getinfo(pycurl.EFFECTIVE_URL)
c.close()

