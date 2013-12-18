#Usage: python eddie-self-deploy.py <version> ['force']
#version can be 'latest', a negative number (e.g. '-4') for how many versions back from latest, or a commit hash
#if version is ommited it falls back to a 'Version' tag on the instance and then 'latest'
#'force' does not check if there is a deploy in progress already

import boto.ec2
import requests
import os
import os.path
import shutil
import sys
import iso8601
import subprocess
import random
import string
import time
import portalocker
import traceback
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
  ec2 = boto.ec2.connect_to_region("us-west-1", aws_access_key_id="AKIAINZ2TABEYCFH7SMQ", aws_secret_access_key="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")
  myID = requests.get("http://169.254.169.254/latest/meta-data/instance-id", timeout=1.0).text
  instance = ec2.get_only_instances([myID])[0]
  spotRequestId = instance.spot_instance_request_id
  if spotRequestId is not None:
    spotRequest = ec2.get_all_spot_instance_requests([spotRequestId])[0]
    tags = spotRequest.tags
    for key, value in tags.items():
      if key not in instance.tags:
        instance.add_tag(key, value)
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

#file based locking

lockFile = None

def aquireLock(): #return truthy when lock aquired
  global lockFile
  if lockFile is None:
    lockFile = open("/home/fortytwo/deploy.lock", "w")
  try:
    portalocker.lock(lockFile, portalocker.LOCK_EX | portalocker.LOCK_NB)
    return True
  except:
    return False


def releaseLock(): #silently fails if there is no lock
  if lockFile is not None:
    try:
      portalocker.unlock(lockFile)
      lockFile.close()
    except:
      pass


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
  conn = S3Connection("AKIAINZ2TABEYCFH7SMQ", "s0asxMClN0loLUHDXe9ZdPyDxJTGdOiquN/SyDLi")
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
  if not os.path.exists("/home/fortytwo/old"):
    os.makedirs("/home/fortytwo/old")
  for the_file in os.listdir("/home/fortytwo/old"):
    shutil.rmtree(os.path.join("/home/fortytwo/old", the_file))
  for item in os.listdir("/home/fortytwo/run"):
    srcpath = os.path.join("/home/fortytwo/run", item)
    dstpath = os.path.join("/home/fortytwo/old", item)
    shutil.move(srcpath, dstpath)
  for the_file in os.listdir("/home/fortytwo/run"):
    shutil.rmtree(os.path.join("/home/fortytwo/run", the_file))
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

#######################
#### Insta Rollback ###
#######################

def instaRollback(serviceType):
  pass #go back to the version in ~/old


if __name__=="__main__":
  try:
    me = whoAmI()
    print me
    serviceType = me['Service']
    version = sys.argv[1] if (len(sys.argv)>1 and sys.argv[1]!='force') else me.get('Version','latest')
    force = 'force' in sys.argv
    name = me['Name']
    deployId = ''.join(random.choice(string.hexdigits) for i in range(3)).lower()
    logHead = "[%s:%s:%s]" % (name, serviceType, deployId)
    if not force and not aquireLock():
      log(logHead + " Deploy in progress. Aborting this one.")
      releaseLock()
      sys.exit(1)
    try:
      log(logHead + " Starting self deploy. Target Version: " + version)

      versionFileName = getNewVersion(serviceType, version)
      if not versionFileName:
        log(logHead + " Failed to retrieve version. Aborting.")
        releaseLock()
        sys.exit(1)
      log(logHead + " Retrieved Version: " + versionFileName)

      if not stopService(serviceType):
        log(logHead + " Failed to stop service. Aborting.")
        releaseLock()
        sys.exit(1)
      log(logHead + " Running service has been stopped.")

      if not setUpCode(serviceType):
        log(logHead + " Failed to install code. Aborting.")
        releaseLock()
        sys.exit(1)
      log(logHead + " Service has been installed.")

      if not startService(serviceType):
        log(logHead + " Failed to start service. Aborting.")
        releaseLock()
        sys.exit(1)
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
      releaseLock()
    except Exception as e:
      traceback.print_exc()
      log(logHead + " Fatal Error during deploy! Machine may need cleanup. (%s)" %str(e))
  except Exception as e:
    log("Fatal Error starting deploy! Could not find machine info. (%s)" % str(e))
    traceback.print_exc()
  finally:
    releaseLock()

