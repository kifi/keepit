#!/usr/bin/python
import requests
import json
import praw
import requests
import sys
from collections import namedtuple

print '########################## Collecting links from Reddit ##########################'
header = u'''
  <!DOCTYPE NETSCAPE-Bookmark-file-1>
  <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
  <TITLE>Bookmarks</TITLE>
  <H1>Bookmarks</H1>
  <DL><p>
  <DT><H3 ADD_DATE="1369451907" LAST_MODIFIED="1387660315" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks Bar</H3>
  <DL><p>
  <DT><H3 ADD_DATE="1387660624" LAST_MODIFIED="1402323481">Resume-1</H3>
  <DL><p>
'''

footer = u'''
  </DL><p>
  </DL><p>
  </DL><p>
'''

item = u'''
<DT><A HREF="{url}" tags="{subreddit}">{title}</A>
'''


SubredditSpec = namedtuple('SubredditSpec', ['name', 'limit', 'cutoff'])

rs = [
  SubredditSpec('design', 100, 25),
  SubredditSpec('truereddit', 100, 70),
  SubredditSpec('economics', 100, 35),
  SubredditSpec('programming', 100, 40),
  SubredditSpec('technology', 100, 150),
  SubredditSpec('travel', 100, 40),
  SubredditSpec('foodforthought', 100, 25),
  SubredditSpec('dataisbeautiful', 100, 25),
  SubredditSpec('history', 100, 25),
  SubredditSpec('psychology', 100, 25),
  SubredditSpec('documentaries', 100, 50),
  SubredditSpec('science', 100, 75),
  SubredditSpec('everythingscience', 100, 40),
  SubredditSpec('frugal', 100, 60),
  SubredditSpec('businesshub', 100, 20),
  SubredditSpec('advertising', 100, 25),
  SubredditSpec('business', 100, 100)
]



output = header

reddit = praw.Reddit(user_agent="reddit_to_bookmark_v0.1")
for r in rs:
  sub = reddit.get_subreddit(r.name)
  ress = [x for x in sub.get_top_from_week(limit=r.limit) if not x.is_self and x.score > r.cutoff and 'imgur' not in x.url]
  for res in ress:
    try:
      output = output + item.format(url=res.url.encode(),subreddit=r.name.encode(),title=res.title.encode())
    except:
      pass
  print "done with", r.name

output = output + footer


f = open('bookmarks.html', 'w')
f.write(output)
f.close()

print '########################## Importing bookmarks to robot user ##########################'
cookies = dict(KIFI_SECURESOCIAL='05a918d9-51dc-42da-9392-a0278260fb69', KIFI_SESSION='8d9d90c008588e194105c0d40ec8631acfd584ad-fortytwo_user_id=9002')

f = open('bookmarks.html', 'r')
bookmarks = f.read()
f.close()
payload = {'file': bookmarks}
url = 'https://www.kifi.com/site/libraries/lB8BIuUOCTi3/import-file'

files = {'file': ('bookmarks.html', open('bookmarks.html', 'rb'), 'text/html', {'Expires': '0'})}
r = requests.post(url, files=files, cookies=cookies)

print 'url: ' + r.url
print '------------------------------------------------'
print 'status code: ' + str(r.status_code)
print '------------------------------------------------'


