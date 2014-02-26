package com.keepit.shoebox


import com.keepit.common.db.slick.RepoModification
import com.keepit.model.{
  SocialConnection,
  UserConnection,
  Invitation,
  SocialUserInfo
}

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}


case class ShoeboxRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}

  //ZZZ actually have logic in here, commented out

  @Provides
  @Singleton
  def socialConnectionChangeListener(): Option[RepoModification.Listener[SocialConnection]] = Some({
    _ => ()
  })

  @Provides
  @Singleton
  def userConnectionChangeListener(): Option[RepoModification.Listener[UserConnection]] = Some({
    _ => ()
  })

  @Provides
  @Singleton
  def invitationChangeListener(): Option[RepoModification.Listener[Invitation]] = Some({
    _ => ()
  })

  @Provides
  @Singleton
  def socialUserChangeListener(): Option[RepoModification.Listener[SocialUserInfo]] = Some({
    _ => ()
  })

}
