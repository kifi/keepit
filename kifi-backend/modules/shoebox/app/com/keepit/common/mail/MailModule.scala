package com.keepit.common.mail

import com.google.inject.{ Provides, Singleton }
import play.api.Play._
import com.keepit.inject.AppScoped
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender, LocalSystemAdminMailSender }
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.actor.ActorInstance
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient
import com.amazonaws.auth.BasicAWSCredentials

case class OptoutSecret(val value: String)

trait MailModule extends ScalaModule {
  @Singleton
  @Provides
  def optoutSecret: OptoutSecret =
    OptoutSecret(current.configuration.getString("optout.secret").get)
}

case class ProdMailModule() extends MailModule {
  def configure() {
    bind[LocalPostOffice].to[ShoeboxPostOfficeImpl]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[MailToKeepPlugin].to[MailToKeepPluginImpl].in[AppScoped]
    bind[SystemAdminMailSender].to[LocalSystemAdminMailSender]
  }

  @Singleton
  @Provides
  def mailToKeepServerSettings: MailToKeepServerSettings = {
    val username = current.configuration.getString("mailtokeep.username").get
    val password = current.configuration.getString("mailtokeep.password").get
    val server = current.configuration.getString("mailtokeep.server").getOrElse("imap.gmail.com")
    val protocol = current.configuration.getString("mailtokeep.protocol").getOrElse("imaps")
    MailToKeepServerSettings(username = username, password = password, server = server, protocol = protocol)
  }
}

case class DevMailModule() extends MailModule {

  protected def whenConfigured[T](parameter: String)(expression: => T): Option[T] =
    current.configuration.getString(parameter).map(_ => expression)

  def configure() {
    bind[LocalPostOffice].to[ShoeboxPostOfficeImpl]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[SystemAdminMailSender].to[LocalSystemAdminMailSender]
    whenConfigured("mailtokeep") { bind[MailToKeepPlugin].to[MailToKeepPluginImpl].in[AppScoped] } getOrElse {
      bind[MailToKeepPlugin].to[FakeMailToKeepPlugin].in[AppScoped]
    }
  }
}

