package com.keepit.common.logging

import grizzled.slf4j.Logger

trait Logging {
  lazy val log = Logger[this.type]
}
