package com.keepit.test

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.eliza.commanders._
import com.keepit.eliza.model.{ MessageRepo, MessageRepoImpl, MessageThreadRepo, UserThreadRepo }
import com.google.inject.Injector
import com.keepit.shoebox.ShoeboxServiceClient

trait ElizaInjectionHelpers { self: TestInjectorProvider =>
  implicit def publicIdConfig(implicit injector: Injector): PublicIdConfiguration = inject[PublicIdConfiguration]

  def messageThreadRepo(implicit injector: Injector) = inject[MessageThreadRepo]
  def userThreadRepo(implicit injector: Injector) = inject[UserThreadRepo]
  def messageRepo(implicit injector: Injector) = inject[MessageRepo]

  def messagingCommander(implicit injector: Injector) = inject[MessagingCommander].asInstanceOf[MessagingCommanderImpl]
  def messageFetchingCommander(implicit injector: Injector) = inject[MessageFetchingCommander]
  def notifCommander(implicit injector: Injector) = inject[NotificationCommander]
  def notificationDeliveryCommander(implicit injector: Injector) = inject[NotificationDeliveryCommander]
  def discussionCommander(implicit injector: Injector) = inject[ElizaDiscussionCommander].asInstanceOf[ElizaDiscussionCommanderImpl]

  def shoebox(implicit injector: Injector) = inject[ShoeboxServiceClient]
}
