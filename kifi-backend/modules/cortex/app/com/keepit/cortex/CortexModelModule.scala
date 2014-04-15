package com.keepit.cortex

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.cortex.models.lda._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped



trait CortexModelModule extends ScalaModule

case class CortexProdModelModule() extends CortexModelModule{
  def configure(){
    bind[LDAURIFeatureUpdatePlugin].to[LDAURIFeatureUpdatePluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersions.denseLDAVersion
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }
}

case class CortexDevModelModule() extends CortexModelModule {
  def configure(){
    bind[LDAURIFeatureUpdatePlugin].to[LDAURIFeatureUpdatePluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersions.denseLDAVersion
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }
}
