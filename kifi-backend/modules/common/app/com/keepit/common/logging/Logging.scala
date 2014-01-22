package com.keepit.common.logging

import play.api.Logger

trait Logging {
  implicit lazy val log = Logger(getClass)
}
