from fabric import api as fapi
import time
import os

fapi.env.user = "fortytwo"

#used to check if other machines are up. 
#Needs to be at least two, so they can check each other.
master_hosts = ['b01', 'b02'] 


def get_hosts(service_type):
    if service_type=="shoebox":
        return ['b01', 'b02']
    elif service_type=="search":
        return ['b04', 'b05']
    else:
        raise Exception("Unknown Service Type: %s" % service_type)


def check_host(host):
    def do_check_up():
        try:
            curlurlurlurl = "http://{0}:9000/internal/clusters/mystatus".format(host)
            result = fapi.run('curl ' + curlurlurlurl)
            return result.lower().strip()=="up"
        except: 
            return False
    return fapi.execute(do_check_up, hosts=[h for h in master_hosts if h!=host][:1]).values()[0]


def verify_up(host, retries):
    """
    Check if the given host is in status "UP", retrying 'retries' times with exponential backoff. 
    """
    retries = int(retries)
    if retries<0:
        return True
    else:
        sleep_time = 4.2
        for i in range(retries):
            if check_host(host):
                return True
            else:
                message_irc("Host %s not up yet. Sleeping %s s." % (host, sleep_time))
                time.sleep(sleep_time)
                sleep_time **= 1.3
        return check_host(host)


def message_irc(msg):
    fapi.local('curl https://grove.io/api/notice/rQX6TOyYYv2cqt4hnDqqwb8v5taSlUdD/ -d "service=deployment" -d "message=[TEST]{0}" -d "url=https://grove.io/app" -d "icon_url=https://grove.io/static/img/avatar.png" &> /dev/null'.format(msg), capture=True)

def get_last(number=1):
    return os.path.splitext(run('ls -Art ~/repo/ | tail -n {0} | head -n 1'.format(number)))[0]

def upload(service_type, jobName='shoebox'):
    fapi.put('/var/lib/jenkins/jobs/{0}/lastSuccessful/archive/kifi-backend/modules/{1}/dist/{1}-*'.format(jobName, service_type), '~/repo')
    with fapi.cd('~/run'):
        fapi.run('unzip -o ~/repo/{0}.zip'.format(get_last()))

def restart(service_type, number=1):
    stop(service_type)
    start(service_type, number)

def stop(service_type):
    message_irc("Taking down %s on %s." % (service_type.upper(), fapi.env.host))
    with cd('~/run'):
        run('/etc/init.d/{0} stop'.format(service_type))

def start(service_type, number=1):
    message_irc("Bringing up %s on %s." % (service_type.upper(), fapi.env.host))
    with cd('~/run'):
        with settings(warn_only=True):
            run('rm -f {0}'.format(service_type))
            run('ln -s {0} {1}'.format(get_last(number), service_type))
        run('/etc/init.d/{0} start'.format(service_type))


def rollback(service_type, host, number=1):
    message_irc("Rolling back %s on %s by %s version." % (service_type.upper(), host, number))
    fapi.execute(restart,service_type, number+1, hosts=[host])    


def deploy(service_type, mode="safe", retries="5", do_rollback=True):
    if mode not in ["safe", "force"]: raise Exception("Invalid Mode %s." % mode)
    if mode=='force': mode="FORCE"
    hosts = get_hosts(service_type)
    message_irc("----- Starting round robin deployment of %s to %s in %s mode." % (service_type.upper(), hosts, mode))
    for host in hosts:
        message_irc("Uploading %s to %s." % (service_type.upper(), host))
        #upload the new version
        fapi.execute(upload, service_type, hosts=[host])
        #restart
        fapi.execute(restart, service_type, hosts=[host])
        #check that we can move on
        is_up = verify_up(host, retries)
        message_irc("Host %s is up." % host) if is_up else message_irc("Host %s is NOT up." % host)
        if mode=="safe" and not is_up:
            #rollback
            if do_rollback: rollback(service_type, host, 1)
            message_irc("Aborting Deploy! (previous hosts may have new version running)")
            break
    message_irc("----- Deployment Ended")







