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
import datetime
import json
from collections import defaultdict
from boto.s3.connection import S3Connection
from boto.s3.key import Key
from dateutil import tz

#######################
#### UTIL #############
#######################

class DeployAbortException(Exception):

  def __init__(self, reason):
    self.reason = reason

def message_slack(msg):
  payload = {
    "type": "message",
    "text": msg,
    "channel": "#deploy",
    "username": "eddie",
    "icon_emoji": ":star2:"
  }
  # webhook url for #deploy channel
  url = 'https://hooks.slack.com/services/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'
  try:
    r = requests.post(url, data=json.dumps(payload))
    if r.status_code != requests.codes.ok:
      print r, r.text
  except Exception as e:
    print 'Unexpected error in message_slack: ', str(e)

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

def logger(head):
  def log(msg):
    print head + msg
    message_slack(head + msg)
  return log

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
####### ASSETS ########
#######################

class S3Asset(object):

  def __init__(self, key):
    self.key = key
    self.name = key.name.split(".")[0]
    (kind, date, time, _, chash) = self.name.split("-")
    self.serviceType = kind
    self.hash = chash
    self.timestamp = datetime.datetime.strptime(date+time, "%Y%m%d%H%M")

  def download(self, target):
    for the_file in os.listdir("/home/fortytwo/repo"): #clean up old zip files
      os.unlink(os.path.join("/home/fortytwo/repo", the_file))
    self.key.get_contents_to_filename(os.path.join("/home/fortytwo/repo", self.key.name))
    if subprocess.call('unzip -qq -o %s -d %s' % (os.path.join("/home/fortytwo/repo", os.listdir("/home/fortytwo/repo")[0]), target), shell=True):
      raise DeployAbortException("Failed to unzip new version")


class S3Assets(object):

  def __init__(self, serviceType, bucket):
    conn = S3Connection("AKIAINZ2TABEYCFH7SMQ", "s0asxMClN0loLUHDXe9ZdPyDxJTGdOiquN/SyDLi")
    bucket = conn.get_bucket(bucket)
    allKeys = bucket.list()
    self.assets = []
    for key in allKeys:
      asset = S3Asset(key)
      if asset.serviceType==serviceType:
        self.assets.append(asset)


  def latest(self):
    inOrder = sorted(self.assets, key = lambda x: x.timestamp)
    if not inOrder:
      raise DeployAbortException("No Version Available!")
    return inOrder[-1]

  def byHash(self, chash):
    versions = [a for a in self.assets if a.hash==chash]
    if not versions:
      raise DeployAbortException("Unknown commit hash: " + chash)
    return versions[0]


class LocalAsset(object):

  def __init__(self, path):
    self.path = path
    folder, filename = os.path.split(path)
    self.location = folder
    self.name = filename.split('.')[0]
    self.alive = True #false when deleted

    try:
      (kind, date, time, _, chash) = self.name.split("-")
      self.serviceType = kind
      self.hash = chash
      self.timestamp = datetime.datetime.strptime(date+time, "%Y%m%d%H%M")
    except ValueError:
      (kind, date, time, _, chash, _) = self.name.split("-")
      self.serviceType = kind
      self.hash = chash
      self.timestamp = datetime.datetime.strptime(date+time, "%Y%m%d%H%M")


  def move(self, destination, suffix=None):
    targetName = self.name
    if suffix is not None:
      targetName = targetName + "-" + suffix
    dstpath = os.path.join(destination, targetName)
    shutil.move(self.path, dstpath)

  def delete(self):
    shutil.rmtree(self.path)
    self.alive = False

class LocalAssets(object):

  def __init__(self, serviceType, folder):
    paths = [os.path.join(folder, f) for f in os.listdir(folder)]
    allAssets = []
    for path in paths:
      try:
        allAssets.append(LocalAsset(path))
      except:
        # print "Failed to parse asset", path
        # import traceback
        # traceback.print_exc()
        pass
    self.assets = [a for a in allAssets if a.serviceType==serviceType]

  def getByName(self, name):
    return [a for a in self.assets if a.name==name][0]

  def contains(self, name):
    return len([a for a in self.assets if a.name==name]) > 0

  def after(self, name):
    reference = self.getByName(name)
    return [a for a in self.assets if a.timestamp > reference.timestamp]

  def fromCurrent(self, idx): #find asset from newest backwards (i.e. idx !<= 0)
    return sorted(self.assets, key = lambda x: x.timestamp)[len(self.assets)-1-abs(idx)]

  def byHash(self, chash): #looks up an asset by its commit hash
    return [a for a in self.assets if a.hash==chash][0]

  def keepOnlyNewest(self, howMany):
    for asset in sorted(self.assets, key = lambda x: x.timestamp)[:-howMany]:
      asset.delete()


#######################
#### STOP  ############
#######################
def stopService(serviceType):
  if os.system("/etc/init.d/" + serviceType + " stop"):
    DeployAbortException("Failed to stop service.")


#######################
##### OTHER  ##########
#######################


def clearSymlink(serviceType):
  try:
    os.remove("/home/fortytwo/run/" + serviceType)
  except:
    print "No symlink to remove"

def createSymlink(serviceType, targetFolder):
  cmd = 'ln -s {0} {1}'.format(os.path.join("/home/fortytwo/run", targetFolder), "/home/fortytwo/run/" + serviceType)
  if os.system(cmd):
    DeployAbortException("Symlink Creation Failed")



#######################
#### START ETC. #######
#######################
def startService(serviceType):
  if os.system("/etc/init.d/" + serviceType + " start"):
    raise DeployAbortException("Failed to start service.")


#######################
######## Main #########
#######################

def isNonPositiveInteger(x):
  try:
    return not int(x) > 0
  except:
    return False

#makes sure that the given version is installed on the machine, fetching it if needed
#should return the full qualified version name
def ensureVersion(serviceType, versionReference):
  #get all local and remote assets
  localAssets = LocalAssets(serviceType, "/home/fortytwo/run")
  s3Assets = S3Assets(serviceType, "fortytwo-builds")

  if versionReference=="latest":
    latest = s3Assets.latest()
    if not localAssets.contains(latest.name):
      latest.download("/home/fortytwo/run")
    return latest.name
  elif isNonPositiveInteger(versionReference):
    return localAssets.fromCurrent(int(versionReference)).name
  else: #it should be a commit hash in here
    try:
      return localAssets.byHash(versionReference).name
    except:
      asset = s3Assets.byHash(versionReference)
      asset.download("/home/fortytwo/run")
      return asset.name


def cleanUp(serviceType, afterVersion):
  localAssets = LocalAssets(serviceType, "/home/fortytwo/run")
  if not os.path.exists("/home/fortytwo/rollback"):
    os.makedirs("/home/fortytwo/rollback")
  for asset in localAssets.after(afterVersion):
    asset.move("/home/fortytwo/rollback", str(time.time()))

  localAssets = LocalAssets(serviceType, "/home/fortytwo/run")
  rolledbackAssets = LocalAssets(serviceType, "/home/fortytwo/rollback")
  localAssets.keepOnlyNewest(5)
  rolledbackAssets.keepOnlyNewest(2)

if __name__ == "__main__":
  try:
    #figure out who I am and what to do
    me = whoAmI()
    serviceType = me['Service']
    version = sys.argv[1] if (len(sys.argv)>1 and sys.argv[1]!='force') else me.get('Version','latest')
    force = 'force' in sys.argv
    name = me['Name']
    deployId = ''.join(random.choice(string.hexdigits) for i in range(3)).lower()
    logHead = "[%s:%s:%s] " % (name, serviceType, deployId)
    log = logger(logHead)
    log("Starting Self-Deploy with version: " + version)
    #are we good to deploy?
    if not force and not aquireLock():
      log("Deploy in progress. Aborting this one.")
      releaseLock()
      sys.exit(1)
    #we now have the lock

    try:
      #step one: make sure we have the requested version on the machine (and fetch it if we don't) -
      log("Making sure requested version is on the machine.")
      fullVersionName = ensureVersion(serviceType, version)

      #step two: stop the service + remove the symlink -
      stopService(serviceType)
      clearSymlink(serviceType)
      log("Running Service has been stopped.")

      #step three: Clean up code
      cleanUp(serviceType, fullVersionName)

      #step four: add the symlink + start the service -
      createSymlink(serviceType, fullVersionName)
      startService(serviceType)
      log("Service has been started. Waiting for it to come up.")

      #step five: wait for service to come up -
      waitStart = time.time()
      maxWaitTime = 1200.0
      waitSoFar = 0
      serviceUp = False
      while (waitSoFar < maxWaitTime and not serviceUp):
        serviceUp = checkUp()
        if not serviceUp:
          if (waitSoFar>0 and int(waitSoFar)%60==0): log("Not up yet. Waited: %ss. Max remaining wait: %ss" %(int(waitSoFar), int(maxWaitTime-waitSoFar)))
          time.sleep(1)
          waitSoFar = time.time() - waitStart
        else:
          break
      if serviceUp:
        log("Service Up. Deploy Finished.")
      else:
        raise DeployAbortException("Service failed to come up. Deployment Failed! Rollback Advised.")
      releaseLock()


    except DeployAbortException as e:
      import traceback
      traceback.print_exc()
      log("ABORT: " + e.reason)
      releaseLock()
      sys.exit(1)
    except Exception as e:
      import traceback
      traceback.print_exc()
      log("FATAL ERROR: " + str(e))
      releaseLock()
      sys.exit(1)


  except Exception as e:
    import traceback
    traceback.print_exc()
    logger("")("Deployment Initialization Fatal Error. (" + str(e) + ")")
    releaseLock()
    sys.exit(1)




