package com.keepit.abook

import com.keepit.inject.InjectorProvider
import com.google.inject.Injector
import com.keepit.abook.model.{ EContactRepo, RichSocialConnectionRepo }
import com.keepit.test.TestInjectorProvider

trait ABookInjectionHelpers { self: TestInjectorProvider =>
  def abookInfoRepo(implicit injector: Injector) = inject[ABookInfoRepo]
  def econtactRepo(implicit injector: Injector) = inject[EContactRepo]
  def richConnectionRepo(implicit injector: Injector) = inject[RichSocialConnectionRepo]
}
