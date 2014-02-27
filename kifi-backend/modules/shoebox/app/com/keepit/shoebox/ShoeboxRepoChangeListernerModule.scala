package com.keepit.shoebox


import com.keepit.common.db.slick.RepoModification
import com.keepit.model.{
  SocialConnection,
  UserConnection,
  Invitation,
  SocialUserInfo
}
import com.keepit.common.queue.{
  CreateRichConnection
}
import com.keepit.commanders.ShoeboxRichConnectionCommander

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}


case class ShoeboxRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}


  @Provides
  @Singleton
  def socialConnectionChangeListener(richConnectionCommander: ShoeboxRichConnectionCommander): Option[RepoModification.Listener[SocialConnection]] = Some(
    repoModification => richConnectionCommander.processSocialConnectionChange(repoModification)
  )

  @Provides
  @Singleton
  def userConnectionChangeListener(richConnectionCommander: ShoeboxRichConnectionCommander): Option[RepoModification.Listener[UserConnection]] = Some({
    repoModification => richConnectionCommander.processUserConnectionChange(repoModification)
  })

  @Provides
  @Singleton
  def invitationChangeListener(richConnectionCommander: ShoeboxRichConnectionCommander): Option[RepoModification.Listener[Invitation]] = Some({
    repoModification => richConnectionCommander.processInvitationChange(repoModification)
  })

  @Provides
  @Singleton
  def socialUserChangeListener(richConnectionCommander: ShoeboxRichConnectionCommander): Option[RepoModification.Listener[SocialUserInfo]] = Some({
    repoModification => richConnectionCommander.processSocialUserChange(repoModification)
  })

}

case class FakeShoeboxRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def socialConnectionChangeListener(): Option[RepoModification.Listener[SocialConnection]] = None

  @Provides
  @Singleton
  def userConnectionChangeListener(): Option[RepoModification.Listener[UserConnection]] = None

  @Provides
  @Singleton
  def invitationChangeListener(): Option[RepoModification.Listener[Invitation]] = None

  @Provides
  @Singleton
  def socialUserChangeListener(): Option[RepoModification.Listener[SocialUserInfo]] = None

}
