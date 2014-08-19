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
  SubredditSpec('design', 100, 20),
  SubredditSpec('music', 100, 200),
  SubredditSpec('worldnews', 100, 1500),
  SubredditSpec('coffee', 100, 25),
  SubredditSpec('truereddit', 100, 50),
  SubredditSpec('economics', 100, 25),
  SubredditSpec('programming', 100, 30),
  SubredditSpec('technology', 100, 100),
  SubredditSpec('travel', 100, 30),
  SubredditSpec('vignettes', 100, 2),
  SubredditSpec('foodforthought', 100, 20),
  SubredditSpec('dataisbeautiful', 100, 20),
  SubredditSpec('politics', 100, 1000),
  SubredditSpec('literature', 100, 10),
  SubredditSpec('history', 100, 20),
  SubredditSpec('psychology', 100, 20),
  SubredditSpec('documentaries', 100, 50),
  SubredditSpec('science', 100, 50),
  SubredditSpec('everythingscience', 100, 20),
  SubredditSpec('frugal', 100, 40)
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
cookies = dict(KIFI_SECURESOCIAL='078672cc-e89f-49e3-83a5-7cc534394ea4', KIFI_SESSION='55af5d45efe547fc51e9f74939ed769b4e8232d7-fortytwo_user_id=9017')

f = open('bookmarks.html', 'r')
bookmarks = f.read()
f.close()
payload = {'file': bookmarks}
url = 'https://www.kifi.com/site/keeps/file-import?public=1'

files = {'file': ('bookmarks.html', open('bookmarks.html', 'rb'), 'text/html', {'Expires': '0'})}
r = requests.post(url, files=files, cookies=cookies)

print 'url: ' + r.url
print '------------------------------------------------'
print 'status code: ' + str(r.status_code)
print '------------------------------------------------'


