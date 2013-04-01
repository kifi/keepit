#ec2 zone url, run `ec2-describe-regions` and select the URL for the correct region
export EC2_URL=https://ec2.us-west-1.amazonaws.com
instanceid=<instance id>
size=20 # in GB


oldvolumeid=$(ec2-describe-instances $instanceid | egrep "^BLOCKDEVICE./dev/sda1" | cut -f3)
zone=$(ec2-describe-instances $instanceid | egrep "^INSTANCE" | cut -f12)
echo "instance $instanceid in $zone with original volume $oldvolumeid"

ec2-stop-instances $instanceid
while ! ec2-detach-volume $oldvolumeid; do sleep 1; done

snapshotid=$(ec2-create-snapshot $oldvolumeid | cut -f2)
echo "This will take a bit..."
while ec2-describe-snapshots $snapshotid | grep -q pending; do sleep 1; done
echo "snapshot: $snapshotid"

newvolumeid=$(ec2-create-volume   --availability-zone $zone   --size $size   --snapshot $snapshotid | cut -f2)
echo "new volume: $newvolumeid"

ec2-attach-volume   --instance $instanceid   --device /dev/sda1   $newvolumeid
while ! ec2-describe-volumes $newvolumeid | grep -q attached; do sleep 1; done
ec2-start-instances $instanceid

while ! ec2-describe-instances $instanceid | grep -q running; do sleep 1; done
ec2-describe-instances $instanceid

# Reattach elastic IPs!

# Connect to the instance, and issue:
# sudo resize2fs /dev/sda1
# or: sudo resize2fs /dev/xvda1


