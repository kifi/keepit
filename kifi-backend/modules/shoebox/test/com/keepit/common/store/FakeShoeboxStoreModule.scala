package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.common.analytics.reports.{InMemoryReportStoreImpl, ReportStore}

case class FakeShoeboxStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def reportStore(): ReportStore = new InMemoryReportStoreImpl()

}
