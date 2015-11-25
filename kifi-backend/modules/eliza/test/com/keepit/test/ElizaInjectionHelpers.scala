package com.keepit.test

import com.keepit.eliza.commanders.{ ElizaDiscussionCommanderImpl, ElizaDiscussionCommander }
import com.keepit.eliza.model.{ UserThreadRepo, MessageRepo, MessageThreadRepo }
import com.keepit.inject._
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.model._
import com.keepit.common.db.FakeSlickSessionProvider
import com.google.inject.Injector

trait ElizaInjectionHelpers { self: TestInjectorProvider =>
  def messageThreadRepo(implicit injector: Injector) = inject[MessageThreadRepo]
  def userThreadRepo(implicit injector: Injector) = inject[UserThreadRepo]
  def messageRepo(implicit injector: Injector) = inject[MessageRepo]

  def discussionCommander(implicit injector: Injector) = inject[ElizaDiscussionCommander].asInstanceOf[ElizaDiscussionCommanderImpl]
}
