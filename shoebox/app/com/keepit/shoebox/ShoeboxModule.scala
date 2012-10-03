package com.keepit.shoebox

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject._
import java.net.InetAddress
import akka.actor.ActorSystem
import akka.actor.Props
import com.keepit.common.actor.ActorPlugin
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckImpl
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.PostOfficeImpl
import com.keepit.common.net._
import com.keepit.inject._
import play.api.Play
import com.keepit.common.mail.MailSender

case class ShoeboxModule() extends ScalaModule {
  def configure(): Unit = {
  }
}