import boto.ec2
import boto.ec2.elb
import sys
from pprint import pprint


ec2 = boto.ec2.connect_to_region("us-west-1")
elb = boto.ec2.elb.connect_to_region('us-west-1')

class ServiceInstance(object):
  def __init__(self, instance):
    self.aws_instance = instance

    self.name = instance.tags["Name"]
    self.service = instance.tags.get("Service", None)
    self.mode = instance.tags.get("Mode", "primary?")
    self.type = instance.instance_type
    self.ip = instance.ip_address
    self.elb = None
    self.elb_status = None

  def matches(self, q):
    if self.name and q in self.name:
      return True
    if self.service and q in self.service:
      return True
    if self.mode and q in self.mode:
      return True
    if self.type and q in self.type:
      return True
    if self.ip and q in self.ip:
      return True
    if self.elb and q in self.elb:
      return True
    return False 

  def __repr__(self):
    #shoebox (mode) b01 on m1.large in elb api-b (status: InService) [ip]
    if self.service:
      if self.elb:
        return "%s (%s) %s on %s in ELB %s (%s) [%s]" % (self.service.upper(), self.mode, self.name, self.type, self.elb, self.elb_status, self.ip)
      else:
        return "%s (%s) %s on %s not in ELB [%s]" % (self.service.upper(), self.mode, self.name, self.type, self.ip)
    else:
      return "Non-Service %s on %s [%s]" % (self.name, self.type, self.ip)


instances = ec2.get_only_instances()

byId = {}
for instance in instances:
  byId[instance.id] = ServiceInstance(instance)


balancers = elb.get_all_load_balancers()

for balancer in balancers:
  bis = balancer.get_instance_health()
  for bi in bis:
    instance = byId[bi.instance_id]
    instance.elb = balancer.name
    instance.elb_status = bi.state

for si in byId.values():
  if len(sys.argv)<2 or si.matches(sys.argv[1]):
    print si
