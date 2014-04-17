package com.keepit.eliza

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import com.keepit.eliza.mail._
import com.keepit.inject.AppScoped

abstract class ElizaMailSettingsModule extends ScalaModule

case class ProdElizaMailSettingsModule() extends ElizaMailSettingsModule {

  @Singleton
  @Provides
  def mailNotificationsServerSettings: MailDiscussionServerSettings = {
    val identifier = current.configuration.getString("mailnotifications.identifier").get
    val domain = current.configuration.getString("mailnotifications.domain").get
    val password = current.configuration.getString("mailnotifications.password").get
    val server = current.configuration.getString("mailnotifications.server").getOrElse("imap.gmail.com")
    val protocol = current.configuration.getString("mailnotifications.protocol").getOrElse("imaps")
    MailDiscussionServerSettings(identifier, domain, password, server, protocol)
  }

  def configure() {
    bind[MailMessageReceiverPlugin].to[MailMessageReceiverPluginImpl].in[AppScoped]
  }
}

case class DevElizaMailSettingsModule() extends ElizaMailSettingsModule {
  def configure() {
    bind[MailMessageReceiverPlugin].to[FakeMailMessageReceiverPlugin].in[AppScoped]
  }
}
