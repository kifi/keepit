import argparse
import sys
import requests
import getpass
import boto.ec2
import spur
import os
import time
from boto.s3.connection import S3Connection
from boto.s3.key import Key
import datetime

userName = None

class S3Asset(object):

  def __init__(self, key):
    self.key = key
    self.name = key.name.split(".")[0]
    (kind, date, time, _, chash) = self.name.split("-")
    self.serviceType = kind
    self.hash = chash
    self.timestamp = datetime.datetime.strptime(date+time, "%Y%m%d%H%M")


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
      raise Exception("No Version Available!")
    return inOrder[-1]

  def byHash(self, chash):
    versions = [a for a in self.assets if a.hash==chash]
    if not versions:
      raise Exception("Unknown commit hash: " + chash)
    return versions[0]



class ServiceInstance(object):
  def __init__(self, instance):
    self.aws_instance = instance

    self.name = instance.tags.get("Name", None)
    self.service = instance.tags.get("Service", None)
    self.mode = instance.tags.get("Mode", "primary?")
    self.type = instance.instance_type
    self.ip = instance.ip_address

  def __repr__(self):
    return "%s (%s) %s on %s [%s]" % (self.service, self.mode, self.name, self.type, self.ip)

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

def log(msg):
  amsg = "[" + userName + "] " + msg
  print amsg
  message_slack(amsg)

def getAllInstances():
  ec2 = boto.ec2.connect_to_region("us-west-1")
  return [ServiceInstance(instance) for instance in ec2.get_only_instances()]

if __name__=="__main__":
  parser = argparse.ArgumentParser(prog="deploy", description="Your friendly FortyTwo Deployment Service v0.42")
  parser.add_argument(
    'serviceType',
    action = 'store',
    help = "Which service type (shoebox, search, etc.) you wish to deploy",
    metavar = "ServiceType"
  )
  parser.add_argument(
    '--host',
    action = 'store',
    help = "Which host (EC2 machine name) to deploy to. (default: all for service type)",
    metavar = "Host"
  )
  parser.add_argument(
    '--mode',
    action = 'store',
    help = "Wait for machines to come up or not. Options: 'safe' or 'force'. (default: 'safe').",
    metavar = "Mode",
    default = 'safe',
    choices = ['safe', 'force']
  )
  parser.add_argument(
    '--version',
    action = 'store',
    help = "Target version. Either a short commit hash, 'latest', or a non positive integer to roll back (e.g. '-1' rolls back by one version). (default: 'latest') ",
    metavar = "Version"
  )
  parser.add_argument(
    '--iam',
    action = 'store',
    help = "Your name, so people can see who is deploying in the slack logs. Please use this! (default: local user name)",
    metavar = "Name"
  )

  args = parser.parse_args(sys.argv[1:])

  if args.iam:
    userName = args.iam
  else:
    userName = getpass.getuser()
    if userName=="fortytwo":
      print "Yo, dude, set your name! ('--iam' option)"

  instances = getAllInstances()

  if args.host:
    instances = [instance for instance in instances if instance.name==args.host]
  else:
    instances = [instance for instance in instances if instance.service==args.serviceType and instance.mode!="canary"]

  assets = S3Assets(args.serviceType, "fortytwo-builds")

  command = ["python", "/home/fortytwo/eddie/eddie-self-deploy.py"]

  assets = S3Assets(args.serviceType, "fortytwo-builds")

  version = 'latest'
  full_version = 'latest'
  if args.version:
    version = args.version
    try:
      full_version = assets.byHash(version).name
    except:
      full_version = version
  else:
    version = assets.latest().hash
    full_version = assets.latest().name + " (latest)"

  command.append(version)

  if args.mode and args.mode=="force":
    command.append("force")

  log("Triggered deploy of %s to %s in %s mode with version %s" % (args.serviceType.upper(), str([str(inst.name) for inst in instances]), args.mode, full_version))

  if args.mode and args.mode=="force":
    try:
      inpt = raw_input("Warning: Force Mode! You sure? [Y,N,Ctrl+C]")
      if inpt=="Y":
        remoteProcs = []
        for instance in instances:
          shell = spur.SshShell(hostname=instance.ip,username="fortytwo", missing_host_key=spur.ssh.MissingHostKey.warn)
          remoteProcs.append(shell.spawn(command, store_pid=True, stdout=sys.stdout))
        log("Deploy Triggered on all instances. Waiting for them to finish.")
        try:
          for remoteProc in remoteProcs:
            while remoteProc.is_running():
              time.sleep(0.1)
            remoteProc.wait_for_result()
        except KeyboardInterrupt:
          log("Manual Abort. Might be too late (force mode).")
          for remoteProc in remoteProcs:
            try:
              remoteProc.send_signal(2)
            except:
              pass
          sys.exit(1)
      else:
        log("Manual Abort.")
    except KeyboardInterrupt:
      log("Manual Abort.")
  else:
    for instance in instances:
      shell = spur.SshShell(hostname=instance.ip, username="fortytwo", missing_host_key=spur.ssh.MissingHostKey.warn)
      remoteProc = shell.spawn(command, store_pid=True, stdout=sys.stdout)
      log("Deploy triggered on " + instance.name + ". Waiting for the machine to finish.")
      try:
        while remoteProc.is_running():
          time.sleep(0.1)
        remoteProc.wait_for_result()
        log("Done with " + instance.name + ".")
      except KeyboardInterrupt:
        log("Manual Abort.")
        remoteProc.send_signal(2)
        sys.exit(1)
      time.sleep(15)
    log("Deployment Complete")


