package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Singleton, Provider, Inject }

@ImplementedBy(classOf[EmailSenderProviderImpl])
trait EmailSenderProvider {
  def connectionMade: FriendConnectionMadeEmailSender
  def friendRequest: FriendRequestEmailSender
  def contactJoined: ContactJoinedEmailSender
  def welcome: WelcomeEmailSender
  def confirmation: EmailConfirmationSender
  def resetPassword: ResetPasswordEmailSender
  def waitList: FeatureWaitlistEmailSender
  def libraryInvite: LibraryInviteEmailSender
  def kifiInvite: InviteToKifiSender
  def activityFeed: ActivityFeedEmailSender
  def twitterWaitlist: TwitterWaitlistEmailSender
  def gratification: GratificationEmailSender
}

@Singleton
class EmailSenderProviderImpl @Inject() (
    private val connectionMadeEmailSender: Provider[FriendConnectionMadeEmailSender],
    private val friendRequestEmailSender: Provider[FriendRequestEmailSender],
    private val welcomeEmailSender: Provider[WelcomeEmailSender],
    private val contactJoinedEmailSender: Provider[ContactJoinedEmailSender],
    private val emailConfirmationSender: Provider[EmailConfirmationSender],
    private val resetPasswordSender: Provider[ResetPasswordEmailSender],
    private val waitListSender: Provider[FeatureWaitlistEmailSender],
    private val libraryInviteEmailSender: Provider[LibraryInviteEmailSender],
    private val inviteToKifiSender: Provider[InviteToKifiSender],
    private val activityFeedSender: Provider[ActivityFeedEmailSender],
    private val twitterWaitlistSender: Provider[TwitterWaitlistEmailSender],
    private val gratificationEmailSender: Provider[GratificationEmailSender]) extends EmailSenderProvider {
  lazy val connectionMade = connectionMadeEmailSender.get()
  lazy val friendRequest = friendRequestEmailSender.get()
  lazy val contactJoined = contactJoinedEmailSender.get()
  lazy val welcome = welcomeEmailSender.get()
  lazy val confirmation = emailConfirmationSender.get()
  lazy val resetPassword = resetPasswordSender.get()
  lazy val waitList = waitListSender.get()
  lazy val libraryInvite = libraryInviteEmailSender.get()
  lazy val kifiInvite = inviteToKifiSender.get()
  lazy val activityFeed = activityFeedSender.get()
  lazy val twitterWaitlist = twitterWaitlistSender.get()
  lazy val gratification = gratificationEmailSender.get()
}
