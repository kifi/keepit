#!/usr/bin/env python

BLOCKSIZE=256*1024*1024
ROOT="../"

import sys
from glob import glob
from zlib import adler32
import os
import pwd
import boto
from boto.s3.key import Key
from multiprocessing.pool import ThreadPool

def compute_hash(files):
  out = []
  for fname in files:
    asum = 1
    with open(fname) as f:
      while True:
        data = f.read(BLOCKSIZE)
        if not data:
          break
        asum = adler32(data, asum)
        if asum < 0:
          asum += 2**32
    sig = hex(asum)[2:10].zfill(8).lower()
    out.append([fname, sig])
  return out

def key_name(orig_name, hash):
  split = orig_name.split("/")
  local = "/".join(split[split.index("modules")+3:])
  local_split = local.split(".")
  if len(local_split) > 1:
    new_file = ".".join(local_split[:-1]) + "." + hash + "." + local_split[-1]
  else:
    new_file = ".".join(local_split) + "." + hash
  return new_file

def get_username():
  return pwd.getpwuid(os.getuid())[0]

def upload_to_s3(orig_name, new_name):
  key = Key(bucket)
  key.key = new_name
  key.set_contents_from_filename(orig_name, replace=False)

def process(file_with_hash):
  orig_name = file_with_hash[0]
  hash = file_with_hash[1]

  new_name = key_name(orig_name, hash)
  print orig_name, new_name
  upload_to_s3(orig_name, new_name)

if __name__=="__main__":
  if len(sys.argv) > 1 and sys.argv[1] is "prod":
    bucket_name = 'assets-b-prod'
  else:
    bucket_name = 'assets-b-dev'

  conn = boto.connect_s3(aws_access_key_id="AKIAJZC6TMAWKQYEGBIQ", aws_secret_access_key="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")
  bucket = conn.get_bucket(bucket_name)

  files = glob(ROOT + "kifi-backend/modules/*/public/**/*.css") + glob(ROOT + "kifi-backend/modules/*/public/**/*.js")
  hashed = compute_hash(files)

  pool = ThreadPool(processes=10)
  pool.map(process, hashed)
