package com.keepit.model

import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Future, Promise }
import com.keepit.common.akka.SafeFuture

