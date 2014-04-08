package com.keepit.cortex

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.cortex.models.lda.LDAModelStore
import com.keepit.cortex.models.lda.LDAWordRepresenter

import net.codingwell.scalaguice.ScalaModule



trait CortexModelModule extends ScalaModule

case class CortexProdModelModule() extends CortexModelModule{
  def configure(){}

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersions.denseLDAVersion
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }
}

case class CortexDevModelModule() extends CortexModelModule {
  def configure(){}

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersions.denseLDAVersion
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }
}
