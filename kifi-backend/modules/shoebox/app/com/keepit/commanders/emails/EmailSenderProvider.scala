package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Singleton, Provider, Inject }

@ImplementedBy(classOf[EmailSenderProviderImpl])
trait EmailSenderProvider {
  def connectionMade: FriendConnectionMadeEmailSender
  def friendRequest: FriendRequestEmailSender
  def contactJoined: ContactJoinedEmailSender
  def welcome: WelcomeEmailSender
  def confirmation: EmailConfirmationSender
}

@Singleton
class EmailSenderProviderImpl @Inject() (
    private val connectionMadeEmailSender: Provider[FriendConnectionMadeEmailSender],
    private val friendRequestEmailSender: Provider[FriendRequestEmailSender],
    private val welcomeEmailSender: Provider[WelcomeEmailSender],
    private val contactJoinedEmailSender: Provider[ContactJoinedEmailSender],
    private val emailConfirmationSender: Provider[EmailConfirmationSender]) extends EmailSenderProvider {
  lazy val connectionMade = connectionMadeEmailSender.get()
  lazy val friendRequest = friendRequestEmailSender.get()
  lazy val contactJoined = contactJoinedEmailSender.get()
  lazy val welcome = welcomeEmailSender.get()
  lazy val confirmation = emailConfirmationSender.get()
}
