package com.keepit.common.logging

import play.api.Logger

trait Logging {
  lazy val log = Logger(getClass)
}
