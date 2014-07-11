package com.keepit.abook

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.db.slick.RepoModification
import com.keepit.commanders.LocalRichConnectionCommander

import com.google.inject.{ Provides, Singleton }
import com.keepit.abook.model.EContact

case class AbookRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def eContactChangeListener(localRichConnectionCommander: LocalRichConnectionCommander): Option[RepoModification.Listener[EContact]] = Some(
    repoModification => localRichConnectionCommander.processEContact(repoModification.model)
  )

}

case class FakeAbookRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def eContactChangeListener(): Option[RepoModification.Listener[EContact]] = None

}
