from fabric.api import *
import os

def get_service_name(host):
    if 'shoebox' in host or host == 'b01':
        return 'shoebox'
    elif 'search' in host or host == 'b04':
        return 'search'
    else:
        raise ValueError('unrecognized server name: {0}'.format(host))

def get_last(number=1):
    return os.path.splitext(run('ls -Art ~/repo/ | tail -n {0} | head -n 1'.format(number)))[0]

@parallel
def upload(jobName='shoebox'):
    service_name = get_service_name(env.host)
    put('/var/lib/jenkins/jobs/{0}/lastSuccessful/archive/kifi-backend/modules/{1}/dist/{1}-*'.format(jobName, service_name), '~/repo')
    with cd('~/run'):
        run('unzip -o ~/repo/{0}.zip'.format(get_last()))

def restart(number=1):
    stop()
    start(number)

def stop():
    service_name = get_service_name(env.host)
    local('curl https://grove.io/api/notice/rQX6TOyYYv2cqt4hnDqqwb8v5taSlUdD/ -d "service=deployment" -d "message=Taking down {0}." -d "url=https://grove.io/app" -d "icon_url=https://grove.io/static/img/avatar.png" &> /dev/null'.format(service_name), capture=True)

    with cd('~/run'):
        run('/etc/init.d/{0} stop'.format(service_name))

def start(number=1):
    service_name = get_service_name(env.host)
    local('curl https://grove.io/api/notice/rQX6TOyYYv2cqt4hnDqqwb8v5taSlUdD/ -d "service=deployment" -d "message=Bringing up {0}." -d "url=https://grove.io/app" -d "icon_url=https://grove.io/static/img/avatar.png" &> /dev/null'.format(service_name), capture=True)

    with cd('~/run'):
        with settings(warn_only=True):
            run('rm -f {0}'.format(service_name))
            run('ln -s {0} {1}'.format(get_last(number), service_name))
        run('/etc/init.d/{0} start'.format(service_name))

