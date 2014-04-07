package com.keepit.cortex

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.cortex.models.lda._
import com.keepit.cortex.core._


trait CortexModelModule extends ScalaModule

case class CortexProdModelModule() extends CortexModelModule{
  def configure(){}

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersion[DenseLDA](1)
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }
}

case class CortexDevModelModule() extends CortexModelModule {
  def configure(){}

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersion[DenseLDA](1)
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }
}
