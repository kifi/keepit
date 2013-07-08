package com.keepit.test

import com.keepit.inject.InjectorProvider
import com.google.inject.Injector
import com.keepit.common.db.slick.{SlickSessionProvider, Database}
import com.keepit.model._
import com.keepit.common.db.TestSlickSessionProvider

trait DeprecatedDbRepos { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[TestSlickSessionProvider]

}
