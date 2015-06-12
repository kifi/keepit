import iso8601
from collections import defaultdict
from boto.s3.connection import S3Connection
from boto.s3.key import Key
from dateutil import tz
from sys import argv


class Asset(object):

  def parseAssetName(self, name):
    return name.split(".")[0].split("-")

  def __init__(self, name, timestamp):
    self.key = name
    (kind, date, time, _, chash) = self.parseAssetName(name)
    self.kind = kind
    self.hash = chash
    self.timestamp = iso8601.parse_date(timestamp)

  def __str__(self):
    return "%s at %s from commit %s" % (self.kind.upper(), self.timestamp.astimezone(tz.tzlocal()), self.hash)

  def __repr__(self):
    return "%s(%s)" % (self.kind.upper()[:2], self.hash)


def getAllAssetsByKind(bucket):
  allKeys = bucket.list()
  byKind = defaultdict(lambda:[])
  for key in allKeys:
    asset = Asset(key.name, key.last_modified)
    byKind[asset.kind].append(asset)
  for kind in byKind:
    byKind[kind].sort(key=lambda x: x.timestamp)
    byKind[kind].reverse()
  return byKind

def maybeInt(x):
  try:
    return int(x)
  except:
    return None

def versionToKey(version, versions):
  if version=="latest":
    return versions[0].key
  versionInt = maybeInt(version)
  if versionInt and versionInt<0:
    return versions[-1*versionInt].key
  else:
    for v in versions:
      if v.hash==version:
        return v.key


if __name__=="__main__":
  conn = S3Connection("AKIAIQRAUTVX6IWOKVGA", "bjy22K4d4WeL1hfp+tahWh6yo4tvgpjdadgCzsGF")
  bucket = conn.get_bucket("fortytwo-builds")
  allAssets = getAllAssetsByKind(bucket)
  if len(argv)<3:
    print "Usage: python eddie-get-version <service-type> <version> [target-folder]"
    print "Available Versions:"
    for kind, versions in allAssets.items():
      print "-------------------" + kind.upper()+ "-------------------"
      for i, version in enumerate(versions):
        axx = "latest" if i==0 else str(-1*i)
        print version, "<" + axx + ">"
  else:
    serviceType = argv[1]
    version = argv[2]
    target = argv[3] if len(argv)>3 else "."
    if not allAssets[serviceType]:
      print "No version for service type:", serviceType
    else:
      keyname = versionToKey(version, allAssets[serviceType])
      key = Key(bucket)
      key.key = keyname
      key.get_contents_to_filename(target + "/" + keyname)
