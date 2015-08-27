import argparse
import sys
import requests
import getpass
import boto.ec2
import spur
import os
import os.path
import time
import portalocker
import mimetypes
import datetime
import json
import shutil
import math
from multiprocessing import Pool
from boto.s3.connection import S3Connection
from boto.s3.key import Key
from filechunkio import FileChunkIO


userName = None

AWS_ACCESS_KEY_ID = "AKIAINZ2TABEYCFH7SMQ"
AWS_SECRET_ACCESS_KEY = "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"
DEFAULT_REGION = "us-west-1"

class FileLock(object):

  def __init__(self, filename):
    self.filename = filename
    self.lockFile = None

  def lock(self):
    if self.lockFile is None:
      self.lockFile = open(self.filename, "w")
    try:
      portalocker.lock(self.lockFile, portalocker.LOCK_EX | portalocker.LOCK_NB)
      return True
    except:
      return False

  def unlock(self):
    try:
      portalocker.unlock(self.lockFile)
      lockFile.close()
    except:
      pass

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
    conn = S3Connection(AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
    self.serviceType = serviceType
    self.bucket = conn.get_bucket(bucket)

  def load(self):
    allKeys = self.bucket.list()
    self.assets = []
    for key in allKeys:
      asset = S3Asset(key)
      if asset.serviceType==self.serviceType:
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
    "icon_emoji": ":star2:",
    "link_names": 1
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

# Adapted from https://github.com/boto/boto/blob/2d7796a625f9596cbadb7d00c0198e5ed84631ed/bin/s3put

def _upload_part(bucketname, multipart_id, part_num,
  source_path, offset, bytes, amount_of_retries=10):
  """
  Uploads a part with retries.
  """

  def _upload(retries_left=amount_of_retries):
    try:

      conn = S3Connection(AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
      bucket = conn.get_bucket(bucketname)
      for mp in bucket.get_all_multipart_uploads():
        if mp.id == multipart_id:
          with FileChunkIO(source_path, 'r', offset=offset, bytes=bytes) as fp:
            mp.upload_part_from_file(fp=fp, part_num=part_num)
          break
    except Exception as exc:
      if retries_left:
        _upload(retries_left=retries_left - 1)
      else:
        print('Failed uploading part #%d' % part_num)
        raise exc
    else:
      if debug == 1:
        print('... Uploaded part #%d' % part_num)

  _upload()


def multipart_upload(bucketname, source_path, keyname, acl='private', headers={}, guess_mimetype=True, parallel_processes=4, region=DEFAULT_REGION):
    """
    Parallel multipart upload.
    """
    conn = boto.s3.connect_to_region(region, aws_access_key_id=AWS_ACCESS_KEY_ID,
                                 aws_secret_access_key=AWS_SECRET_ACCESS_KEY)
    bucket = conn.get_bucket(bucketname)

    if guess_mimetype:
      mtype = mimetypes.guess_type(keyname)[0] or 'application/octet-stream'
      headers.update({'Content-Type': mtype})

    mp = bucket.initiate_multipart_upload(keyname, headers=headers)

    source_size = os.stat(source_path).st_size
    bytes_per_chunk = max(int(math.sqrt(5242880) * math.sqrt(source_size)),
                          5242880)
    chunk_amount = int(math.ceil(source_size / float(bytes_per_chunk)))

    pool = Pool(processes=parallel_processes)
    for i in range(chunk_amount):
      offset = i * bytes_per_chunk
      remaining_bytes = source_size - offset
      bytes = min([bytes_per_chunk, remaining_bytes])
      part_num = i + 1
      pool.apply_async(_upload_part, [bucketname, mp.id,
        part_num, source_path, offset, bytes])
    pool.close()
    pool.join()

    if len(mp.get_all_parts()) == chunk_amount:
      mp.complete_upload()
      key = bucket.get_key(keyname)
      key.set_acl(acl)
    else:
      mp.cancel_upload()

def getAllInstances():
  conn = boto.ec2.connect_to_region("us-west-1")
  live_instances = [x for x in conn.get_only_instances() if x.state != 'terminated']
  return [ServiceInstance(instance) for instance in live_instances]

if __name__=="__main__":
  try:
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
      default = 'latest',
      metavar = "Version"
    )
    parser.add_argument(
      '--iam',
      action = 'store',
      help = "Your name, so people can see who is deploying in the slack logs. Please use this! (default: local user name)",
      metavar = "Name"
    )
    parser.add_argument(
      '--nolock',
      action = 'store_true',
      help = "Ignore the local lock, so multiple deploys of the same service can happen at once",
    )


    args = parser.parse_args(sys.argv[1:])

    if args.iam:
      userName = args.iam
    else:
      userName = getpass.getuser()
      if userName=="eng":
        print "Yo, dude, set your name! ('--iam' option)"

    lock = FileLock("/home/eng/" + args.serviceType + ".lock")

    instances = getAllInstances()

    if args.host:
      instances = [instance for instance in instances if instance.name==args.host]
    else:
      instances = [instance for instance in instances if instance.service==args.serviceType and instance.mode!="canary"]

    command = ["python", "/home/fortytwo/eddie/eddie-self-deploy.py"]

    assets = S3Assets(args.serviceType, "fortytwo-builds")

    if args.version == 'latest':

      last_build = requests.get('http://localhost:8080/job/all-quick-s3/lastStableBuild/api/json').json()
      log("Uploading assets for build %s" % (last_build['fullDisplayName']))

      asset_basenames = []
      for artifact in last_build['artifacts']:
        relative_path = artifact['relativePath']
        asset_basenames.append(os.path.basename(relative_path))
        source_path = 'deploy-tmp/%s' % relative_path
        os.makedirs(os.path.dirname(source_path))
        jenkins_file = requests.get('http://localhost:8080/job/all-quick-s3/lastStableBuild/artifact/%s' % relative_path)
        with open(source_path, 'wb') as handle:
          for chunk in jenkins_file.iter_content(1024):
            handle.write(chunk)
        multipart_upload('fortytwo-builds', source_path, os.path.basename(source_path))
        log('Uploaded build asset %s' % relative_path)

      shutil.rmtree('deploy-tmp/')

      latest_asset = [
        asset for asset in (S3Asset(Key("fortytwo-builds", name)) for name in asset_basenames)
        if asset.serviceType == args.serviceType
        ][0]
      version = latest_asset.hash
      full_version = latest_asset.name + " (latest)"
    else:
      assets.load()
      version = args.version
      try:
        full_version = assets.byHash(version).name
      except:
        full_version = version

    slack_version = "<https://github.com/kifi/keepit/commit/" + version + "|" + full_version + ">"

    command.append(version)

    if args.mode and args.mode=="force":
      command.append("force")
    else:
      if (not args.nolock) and (not lock.lock()):
        print "There appears to be a deploy already in progress for " + args.serviceType + ". Please try again later. We appreciate your business."
        sys.exit(0)

    log("Deploying %s to %s (%s): %s" % (args.serviceType.upper(), str([str(inst.name) for inst in instances]), args.mode, slack_version))

    if args.mode and args.mode=="force":
      try:
        inpt = raw_input("Warning: Force Mode! You sure? [Y,N,Ctrl+C]")
        if inpt=="Y":
          remoteProcs = []
          for instance in instances:
            shell = spur.SshShell(hostname=instance.ip,username="fortytwo", missing_host_key=spur.ssh.MissingHostKey.warn, private_key_file="/home/eng/.ssh/id_rsa")
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
        shell = spur.SshShell(hostname=instance.ip, username="fortytwo", missing_host_key=spur.ssh.MissingHostKey.warn, private_key_file="/home/eng/.ssh/id_rsa")
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
          lock.unlock()
          sys.exit(1)
        time.sleep(5)
      lock.unlock()
      log("Deployment Complete")
  except Exception, e:
    log("@channel: FATAL ERROR: " + str(e))
    raise

