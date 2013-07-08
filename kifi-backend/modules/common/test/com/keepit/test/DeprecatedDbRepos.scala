package com.keepit.test

import com.keepit.inject.InjectorProvider
import com.google.inject.Injector
import com.keepit.common.db.slick.{SlickSessionProvider, Database}
import com.keepit.model._
import com.keepit.common.db.TestSlickSessionProvider

trait DeprecatedDbRepos { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]
  def urlRepo(implicit injector: Injector) = inject[URLRepo]
  def socialUserInfoRepo(implicit injector: Injector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: Injector) = inject[KifiInstallationRepo]
  def unscrapableRepo(implicit injector: Injector) = inject[UnscrapableRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[TestSlickSessionProvider]

}
