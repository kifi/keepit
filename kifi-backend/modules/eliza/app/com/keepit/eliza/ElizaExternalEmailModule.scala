package com.keepit.eliza

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import com.keepit.eliza.mail._
import com.keepit.inject.AppScoped
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.{FakeSQSQueue, QueueName, SimpleSQSClient, SQSQueue}
import com.keepit.eliza.mail.MailDiscussionServerSettings
import com.amazonaws.regions.Regions

abstract class ElizaExternalEmailModule extends ScalaModule

case class ProdElizaExternalEmailModule() extends ElizaExternalEmailModule {

  def configure() {
    bind[MailMessageReceiverPlugin].to[MailMessageReceiverPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def mailNotificationsServerSettings: MailDiscussionServerSettings = {
    val identifier = current.configuration.getString("mail-notifications.identifier").get
    val domain = current.configuration.getString("mail-notifications.domain").get
    val password = current.configuration.getString("mail-notifications.password").get
    val server = current.configuration.getString("mail-notifications.server").getOrElse("imap.gmail.com")
    val protocol = current.configuration.getString("mail-notifications.protocol").getOrElse("imaps")
    MailDiscussionServerSettings(identifier, domain, password, server, protocol)
  }


  @Singleton
  @Provides
  def mailNotificationReplyQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[MailNotificationReply] = {
    val queueName = QueueName(current.configuration.getString("mail-notifications.queue-name").get)
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[MailNotificationReply](queueName)
  }
}

case class DevElizaExternalEmailModule() extends ElizaExternalEmailModule {
  def configure() {
    bind[MailMessageReceiverPlugin].to[FakeMailMessageReceiverPlugin].in[AppScoped]
  }

  @Singleton
  @Provides
  def mailNotificationReplyQueue(): SQSQueue[MailNotificationReply] = {
    new FakeSQSQueue[MailNotificationReply]{}
  }
}
