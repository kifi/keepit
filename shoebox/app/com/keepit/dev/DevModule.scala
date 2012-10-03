package com.keepit.dev

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.Provides
import com.google.inject.Provider
import akka.actor.ActorSystem
import akka.actor.Props
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.PostOfficeImpl
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.inject._
import com.keepit.common.logging.Logging

case class DevModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    log.info("configuring dev module")
    log.info("done configuring dev module")
  }
}
