package com.keepit.common.mail

import com.google.inject.{Provides, Singleton}
import play.api.Play._
import com.keepit.inject.AppScoped
import com.keepit.common.healthcheck.{HealthcheckMailSender, LocalHealthcheckMailSender}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.actor.ActorInstance

trait MailModule extends ScalaModule

case class ProdMailModule() extends MailModule {
  def configure() {
    bind[LocalPostOffice].to[ShoeboxPostOfficeImpl]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[MailToKeepPlugin].to[MailToKeepPluginImpl].in[AppScoped]
    bind[HealthcheckMailSender].to[LocalHealthcheckMailSender]
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

  def configure {
    bind[LocalPostOffice].to[ShoeboxPostOfficeImpl]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[HealthcheckMailSender].to[LocalHealthcheckMailSender]
  }
  @Provides
  @Singleton
  def mailToKeepServerSettingsOpt: Option[MailToKeepServerSettings] =
    current.configuration.getString("mailtokeep").map(_ => ProdMailModule().mailToKeepServerSettings)

  @Provides
  @Singleton
  def mailToKeepServerSettings: MailToKeepServerSettings = mailToKeepServerSettingsOpt.get

  @AppScoped
  @Provides
  def mailToKeepPlugin(
    actor: ActorInstance[MailToKeepActor],
    mailToKeepServerSettings: Option[MailToKeepServerSettings],
    schedulingProperties: SchedulingProperties): MailToKeepPlugin = {
    mailToKeepServerSettingsOpt match {
      case None => new FakeMailToKeepPlugin()
      case _ => new MailToKeepPluginImpl(actor, schedulingProperties)
    }
  }
}

