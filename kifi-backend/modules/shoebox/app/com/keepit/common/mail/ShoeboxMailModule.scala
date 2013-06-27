package com.keepit.common.mail

import com.google.inject.{Provides, Singleton}
import play.api.Play._
import com.keepit.inject.AppScoped

case class ShoeboxMailModule() extends MailModule {
  def configure() {
    bind[LocalPostOffice].to[ShoeboxPostOfficeImpl]
    bind[MailSenderPlugin].to[MailSenderPluginImpl].in[AppScoped]
    bind[MailToKeepPlugin].to[MailToKeepPluginImpl].in[AppScoped]
    bind[InvitationMailPlugin].to[InvitationMailPluginImpl].in[AppScoped]
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
