package com.keepit.abook

import com.keepit.inject.InjectorProvider
import com.google.inject.Injector

trait ABookInjectionHelpers { self: InjectorProvider =>
  def abookInfoRepo(implicit injector:Injector) = inject[ABookInfoRepo]
  def contactRepo(implicit injector:Injector) = inject[ContactRepo]
  def econtactRepo(implicit injector:Injector) = inject[EContactRepo]
}
