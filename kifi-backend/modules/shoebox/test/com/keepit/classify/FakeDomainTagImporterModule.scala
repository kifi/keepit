package com.keepit.classify

import com.google.inject.{ Provides, Singleton }

case class FakeDomainTagImporterModule() extends DomainTagImporterModule {

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = DomainTagImportSettings(localDir = "", url = "")
}
