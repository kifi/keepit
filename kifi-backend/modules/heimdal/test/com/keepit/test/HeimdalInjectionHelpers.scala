package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.common.db.FakeSlickSessionProvider
import com.google.inject.Injector
import com.keepit.model.helprank.{ UserBookmarkClicksRepo, KeepDiscoveryRepo, ReKeepRepo }

trait HeimdalInjectionHelpers { self: TestInjectorProvider =>

  def userBookmarkClicksRepo(implicit injector: Injector) = inject[UserBookmarkClicksRepo]
  def keepDiscoveryRepo(implicit injector: Injector) = inject[KeepDiscoveryRepo]
  def rekeepRepo(implicit injector: Injector) = inject[ReKeepRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[FakeSlickSessionProvider]

}
