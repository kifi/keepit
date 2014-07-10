package com.keepit.classify

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.google.common.io.Files

trait DomainTagImporterModule extends ScalaModule {
  def configure() {}
}

case class ProdDomainTagImporterModule() extends DomainTagImporterModule {

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    val dirPath = Files.createTempDir().getAbsolutePath
    DomainTagImportSettings(localDir = dirPath, url = "http://www.komodia.com/clients/42.zip")
  }
}

case class DevDomainTagImporterModule() extends DomainTagImporterModule {

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    DomainTagImportSettings(localDir = Files.createTempDir().getAbsolutePath, url = "http://localhost:8000/42.zip")
  }
}