import argparse
import sys
import requests
import getpass
import boto.ec2
import spur
import os
import time

class ServiceInstance(object):
  def __init__(self, instance):
    self.aws_instance = instance

    self.name = instance.tags["Name"]
    self.service = instance.tags.get("Service", None)
    self.mode = instance.tags.get("Mode", "primary?")
    self.type = instance.instance_type
    self.ip = instance.ip_address

  def __repr__(self):
    return "%s (%s) %s on %s [%s]" % (self.service, self.mode, self.name, self.type, self.ip)

def message_irc(msg):
  data = {
    'service': 'deployment',
    'url': 'https://grove.io/app',
    'icon_url': 'https://grove.io/static/img/avatar.png',
    'message': msg
  }
  requests.post("https://grove.io/api/notice/rQX6TOyYYv2cqt4hnDqqwb8v5taSlUdD/", data=data)

def log(msg):
  amsg = "[" + getpass.getuser() + "] " + msg
  print amsg
  message_irc(amsg)

def getAllInstances():
  ec2 = boto.ec2.connect_to_region("us-west-1")
  return [ServiceInstance(instance) for instance in ec2.get_only_instances()]

if __name__=="__main__":
  parser = argparse.ArgumentParser(description="Your friendly FortyTwo Deployment Service v0.42")
  parser.add_argument(
    'serviceType',
    action = 'store',
    help = "Which service type (shoebox, search, etc.) you which to deploy",
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
    help = "Wait for machines to come up or not (default: 'safe')",
    metavar = "Host",
    default = 'safe',
    choices = ['safe', 'force']
  )
  parser.add_argument(
    '--version',
    action = 'store',
    help = "Target version",
    metavar = "Version"
  )

  args = parser.parse_args(sys.argv[1:])
  instances = getAllInstances()

  if args.host:
    instances = [instance for instance in instances if instance.name==args.host]
  else:
    instances = [instance for instance in instances if instance.service==args.serviceType]

  log("Triggered deploy of %s to %s in %s mode" % (args.serviceType.upper(), str([str(inst.name) for inst in instances]), args.mode))

  command = ["python", "/home/fortytwo/eddie/eddie-self-deploy.py"]

  if args.version:
    command.append(args.version)

  if args.mode and args.mode=="force":
    command.append("force")

  for instance in instances:
    shell = spur.SshShell(hostname=instance.ip,username="fortytwo", missing_host_key=spur.ssh.MissingHostKey.warn)
    remoteProc = shell.spawn(command, store_pid=True, stdout=sys.stdout)
    try:
      while remoteProc.is_running():
        time.sleep(1)
    except KeyboardInterrupt:
      log("Manual Abort.")
      remoteProc.send_signal(2)
      sys.exit(1)



