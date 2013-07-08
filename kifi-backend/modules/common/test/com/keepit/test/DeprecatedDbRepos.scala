package com.keepit.test

import com.keepit.inject.InjectorProvider
import com.google.inject.Injector
import com.keepit.common.db.slick.{SlickSessionProvider, Database}
import com.keepit.model._
import com.keepit.common.db.TestSlickSessionProvider

trait DeprecatedDbRepos { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]
  def userSessionRepo(implicit injector: Injector) = inject[UserSessionRepo]
  def userConnRepo(implicit injector: Injector) = inject[UserConnectionRepo]
  def socialConnRepo(implicit injector: Injector) = inject[SocialConnectionRepo]
  def urlRepo(implicit injector: Injector) = inject[URLRepo]
  def commentRepo(implicit injector: Injector) = inject[CommentRepo]
  def commentReadRepo(implicit injector: Injector) = inject[CommentReadRepo]
  def commentRecipientRepo(implicit injector: Injector) = inject[CommentRecipientRepo]
  def socialUserInfoRepo(implicit injector: Injector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: Injector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: Injector) = inject[UserExperimentRepo]
  def emailAddressRepo(implicit injector: Injector) = inject[EmailAddressRepo]
  def unscrapableRepo(implicit injector: Injector) = inject[UnscrapableRepo]
  def notificationRepo(implicit injector: Injector) = inject[UserNotificationRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[TestSlickSessionProvider]

}
