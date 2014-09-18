package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Singleton, Provider, Inject }

@ImplementedBy(classOf[EmailSenderProviderImpl])
trait EmailSenderProvider {
  def connectionMade: FriendConnectionMadeEmailSender
  def friendRequest: FriendRequestEmailSender
  def contactJoined: ContactJoinedEmailSender
}

@Singleton
class EmailSenderProviderImpl @Inject() (
    private val connectionMadeEmailSender: Provider[FriendConnectionMadeEmailSender],
    private val friendRequestEmailSender: Provider[FriendRequestEmailSender],
    private val contactJoinedEmailSender: Provider[ContactJoinedEmailSender]) extends EmailSenderProvider {
  lazy val connectionMade = connectionMadeEmailSender.get()
  lazy val friendRequest = friendRequestEmailSender.get()
  lazy val contactJoined = contactJoinedEmailSender.get()
}
