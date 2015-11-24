package com.keepit.test

import com.keepit.eliza.commanders.{ ElizaDiscussionCommanderImpl, ElizaDiscussionCommander }
import com.keepit.inject._
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.model._
import com.keepit.common.db.FakeSlickSessionProvider
import com.google.inject.Injector

trait ElizaInjectionHelpers { self: TestInjectorProvider =>
  def discussionCommander(implicit injector: Injector) = inject[ElizaDiscussionCommander].asInstanceOf[ElizaDiscussionCommanderImpl]
}
