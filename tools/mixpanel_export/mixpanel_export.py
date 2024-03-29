#! /usr/bin/env python

from mixpanel import Mixpanel

from datetime import datetime, timedelta
import errno
import os
import requests
import sys
import urllib2
import shutil
import subprocess

MP_API_KEY = 'c17a5e3624c92cf4aa4d995b4b8bb589'
MP_API_SECRET = '0b22bedb78ec2fa9d390110be35dfb2a'

EXPORT_DIR = os.path.dirname(os.path.realpath(__file__)) + '/exports';

class MixpanelDataExporter(object):

    def __init__(self, api):
        self.api = api

    def export(self, from_date, to_date):
        params = {
            'from_date': from_date.strftime("%Y-%m-%d"),
            'to_date': to_date.strftime("%Y-%m-%d")
        }

        save_as = EXPORT_DIR + "/mixpanel-{}-{}.txt".format(from_date.strftime("%Y%m%d"), to_date.strftime("%Y%m%d"))
        compressed_save_as = save_as + ".bz2"
        if os.path.isfile(save_as):
            print "File already exists: %s" % save_as
        elif os.path.isfile(compressed_save_as):
            print "File already exists: %s" % compressed_save_as
        else:
            request_url = self.request_url(params)
            print "Downloading: from_date=%s to_date=%s" % (from_date, to_date)
            self.download(request_url, save_as)
            print "Done"
            self.compress(save_as)

        return save_as

    def compress(self, filename):
        print "Running bzip on %s" % filename
        subprocess.call(["bzip2", filename])
        print "Done"

    def request_url(self, params):
        return self.api.request(params)

    def download(self, request_url, save_as):
        print "download(%s, %s): " % (request_url, save_as),
        response = requests.get(request_url, stream=True)
        if not response.ok:
            print "request failed"
            return False # TODO handle

        with open(save_as, 'wb') as fp:
            print "downloading"
            progress_ticks = 0
            checkpoint = 0
            size = 0

            for block in response.iter_content(1024):
                fp.write(block)

                size += len(block)
                checkpoint += len(block)

                if checkpoint > 1024*1024:
                    sys.stdout.write('.')
                    sys.stdout.flush()
                    progress_ticks += 1
                    checkpoint = 0
                    if progress_ticks % 10 == 0:
                        print " %.1f MB" % (size / (1024*1024))

        return

def printUsage():
    print "Usage: %s YYYY-MM-DD YYYY-MM-DD" % sys.argv[0]
    print "\nexport raw logs from mixpanel between 2 dates (inclusive)"

def mkdir_p(path):
    try:
        os.makedirs(path)
    except OSError as exc: # Python >2.5
        if exc.errno == errno.EEXIST and os.path.isdir(path):
            pass
        else: raise

def daterange(start_date, end_date):
    for n in range(int ((end_date - start_date).days)):
        yield start_date + timedelta(n)

if __name__ == '__main__':
    mkdir_p(EXPORT_DIR)

    api = Mixpanel(MP_API_KEY, MP_API_SECRET)
    exporter = MixpanelDataExporter(api)

    if len(sys.argv) != 3:
        printUsage()
        sys.exit(1)

    from_date = datetime.strptime(sys.argv[1], "%Y-%m-%d")
    to_date = datetime.strptime(sys.argv[2], "%Y-%m-%d")

    for single_date in daterange(from_date, to_date):
        exporter.export(single_date, single_date)
