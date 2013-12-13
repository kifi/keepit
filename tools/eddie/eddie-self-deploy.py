#usage python deployyourself.py <version> [force]

#Todo
#on system startup deployyourself latest
#need way of checking if a deploy in in progress, ELB registration
#feature to lock deploy
#init scripts heaps size + wait time
#nginx clean
#aws keys
#get version from tag
#instant rollback



#Flow::

#write deply in progress file

#first, figure out what service I am

#prepare for new version:
#verify version exists
#clear out ~/repo
#download new version

#remove from ELB if neccessary

#shutdown and clean:
#stop current service if it's running
#delete symlink
#move to ~/old
#at this point ~/run is empty

#start up new service:
#unpack single file in ~/repo to ~/run
#make symlink
#call init script
#wait certain amount of time until I'm up, or else abort

#add to ELB if neccessary

#delete deploy in progress file
#end execution

import boto.ec2
import requests
import os
import shutil
import sys
import iso8601
import subprocess
import random
import string
import time
from collections import defaultdict
from boto.s3.connection import S3Connection
from boto.s3.key import Key
from dateutil import tz

#######################
#### UTIL #############
#######################

def message_irc(msg):
  data = {
    'service': 'deployment',
    'url': 'https://grove.io/app',
    'icon_url': 'https://grove.io/static/img/avatar.png',
    'message': msg
  }
  requests.post("https://grove.io/api/notice/rQX6TOyYYv2cqt4hnDqqwb8v5taSlUdD/", data=data)

def abort(why):
  pass

def whoAmI():
  ec2 = boto.ec2.connect_to_region("us-west-1")
  myID = requests.get("http://169.254.169.254/latest/meta-data/instance-id", timeout=1.0).text
  return ec2.get_only_instances([myID])[0].tags

def log(msg):
  print msg
  message_irc(msg)

def checkUp():
  try:
    resp = requests.get("http://localhost:9000/up", timeout=1.0)
    return resp.status_code==200
  except:
    return False



#######################
#### S3 LOAD ##########
#######################

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

def getNewVersion(serviceType, version):
  conn = S3Connection()
  bucket = conn.get_bucket("fortytwo-builds")
  allAssets = getAllAssetsByKind(bucket)
  def showAvailable():
    print "Available Versions:"
    for kind, versions in allAssets.items():
      print "-------------------" + kind.upper()+ "-------------------"
      for i, version in enumerate(versions):
        axx = "latest" if i==0 else str(-1*i)
        print version, "<" + axx + ">"
  target = "/home/fortytwo/repo"
  if not allAssets[serviceType]:
    print "ERROR => No version for service type:", serviceType
    showAvailable()
  else:
    keyname = versionToKey(version, allAssets[serviceType])
    if keyname is None:
      print "ERROR => Not a valid version:", version
      showAvailable()
    else:
      for the_file in os.listdir("/home/fortytwo/repo"):
        os.unlink(os.path.join("/home/fortytwo/repo", the_file))
      key = Key(bucket)
      key.key = keyname
      key.get_contents_to_filename(target + "/" + keyname)
      return keyname


#######################
#### STOP  ############
#######################
def stopService(serviceType):
  return not os.system("/etc/init.d/" + serviceType + " stop")


#######################
#### UNPACK  ##########
#######################

def setUpCode(serviceType):
  try:
    os.remove("/home/fortytwo/run/" + serviceType)
  except:
    print "No symlink to remove"
  for the_file in os.listdir("/home/fortytwo/old"):
    shutil.rmtree(os.path.join("/home/fortytwo/old", the_file))
  for item in os.listdir("/home/fortytwo/run"):
    srcpath = os.path.join("/home/fortytwo/run", item)
    dstpath = os.path.join("/home/fortytwo/old", item)
    shutil.move(srcpath, dstpath)
  if subprocess.call('unzip -qq -o %s -d /home/fortytwo/run' % os.path.join("/home/fortytwo/repo", os.listdir("/home/fortytwo/repo")[0]), shell=True): return False
  cmd = 'ln -s {0} {1}'.format(os.path.join("/home/fortytwo/run", os.listdir("/home/fortytwo/run")[0]), "/home/fortytwo/run/" + serviceType)
  if os.system(cmd):
    print "symlink creation failed"
    return False
  return True



#######################
#### START  ###########
#######################
def startService(serviceType):
  return not os.system("/etc/init.d/" + serviceType + " start")



#proper aborting, file lock with 'force' mode


if __name__=="__main__":
  try:
    me = whoAmI()
    serviceType = "shoebox" #me['Service']
    version = sys.argv[1] if len(sys.argv)>1 else 'latest'
    name = me['Name']
    deployId = ''.join(random.choice(string.hexdigits) for i in range(3)).lower()
    logHead = "[%s:%s:%s]" % (name, serviceType, deployId)
    try:
      log(logHead + " Starting self deploy. Target Version: " + version)

      if not stopService(serviceType): sys.exit(1)
      log(logHead + " Running service has been stopped.")

      versionFileName = getNewVersion(serviceType, version)
      if not versionFileName: sys.exit(1)
      log(logHead + " Retrieved Version: " + versionFileName)

      if not setUpCode(serviceType): sys.exit(1)
      log(logHead + " Service has been installed.")

      if not startService(serviceType): sys.exit(1)
      log(logHead + " Service has been started. Waiting for it to come up.")

      waitStart = time.time()
      maxWaitTime = 1200.0
      waitSoFar = 0
      serviceUp = False
      while (waitSoFar < maxWaitTime and not serviceUp):
        serviceUp = checkUp()
        if not serviceUp:
          if (waitSoFar>0 and int(waitSoFar)%20==0): log(logHead + " Not up yet. Waited: %ss. Max remaining wait: %ss" %(int(waitSoFar), int(maxWaitTime-waitSoFar)))
          time.sleep(1)
          waitSoFar = time.time() - waitStart
        else:
          break

      if serviceUp:
        log(logHead + " Service Up. Deploy Finished.")
      else:
        log(logHead + " Service failed to come up. Deployment Failed!")
    except Exception as e:
      log(logHead + " Fatal Error during deploy! Machine may need cleanup. (%s)" %str(e))
  except Exception as e:
    log("Fatal Error starting deploy! Could not find machine info. (%s)" % str(e))

