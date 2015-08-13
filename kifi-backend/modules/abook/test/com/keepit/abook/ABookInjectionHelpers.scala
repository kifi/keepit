package com.keepit.abook

import com.google.inject.Injector
import com.keepit.abook.model.EContactRepo
import com.keepit.test.TestInjectorProvider

trait ABookInjectionHelpers { self: TestInjectorProvider =>
  def abookInfoRepo(implicit injector: Injector) = inject[ABookInfoRepo]
  def econtactRepo(implicit injector: Injector) = inject[EContactRepo]
}
