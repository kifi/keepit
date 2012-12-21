from __future__ import with_statement
from fabric.api import *
from fabric.utils import *
from fabric.contrib.console import confirm
from fabric.operations import run, put
from time import sleep

def chrome():
  local("rsync -vrc --delete out/chrome ~/Dropbox/keepit")
