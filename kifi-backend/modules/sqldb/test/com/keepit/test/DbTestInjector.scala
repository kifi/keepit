package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.{ SlickSessionProvider, Database }
import com.keepit.model._
import com.keepit.common.db.TestSlickSessionProvider
import com.google.inject.Injector
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RWSession
import play.api.{ Application, Mode }
import com.google.inject.util.Modules
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.db.TestSlickModule
import com.keepit.common.db.{ TestDbInfo }
import com.keepit.common.cache.{ HashMapMemoryCacheModule, DbCacheModule }

trait DbTestInjector extends EmptyInjector with DbInjectionHelper {
  val mode = Mode.Test
  val module = Modules.combine(FakeClockModule(), FakeHealthcheckModule(), TestSlickModule(TestDbInfo.dbInfo), DbCacheModule(HashMapMemoryCacheModule()))
}