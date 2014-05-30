package com.keepit.cortex

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.cortex.models.lda._
import com.keepit.cortex.models.word2vec._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.common.logging.Logging



trait CortexModelModule extends ScalaModule

case class CortexProdModelModule() extends CortexModelModule with Logging{
  def configure(){
    bind[LDAURIFeatureUpdatePlugin].to[LDAURIFeatureUpdatePluginImpl].in[AppScoped]
    bind[Word2VecURIFeatureUpdatePlugin].to[Word2VecURIFeatureUpdatePluginImpl].in[AppScoped]
    bind[RichWord2VecURIFeatureUpdatePlugin].to[RichWord2VecURIFeatureUpdatePluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    log.info("loading lda from model store")
    val version = ModelVersions.denseLDAVersion
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }

  @Singleton
  @Provides
  def word2vecWordRepresenter(store: Word2VecStore): Word2VecWordRepresenter = {
    log.info("loading word2vec from model store")
    val version = ModelVersions.word2vecVersion
    val word2vec = store.get(version).get
    Word2VecWordRepresenter(version, word2vec)
  }
}

case class CortexDevModelModule() extends CortexModelModule {
  def configure(){
    bind[LDAURIFeatureUpdatePlugin].to[LDAURIFeatureUpdatePluginImpl].in[AppScoped]
    bind[Word2VecURIFeatureUpdatePlugin].to[Word2VecURIFeatureUpdatePluginImpl].in[AppScoped]
    bind[RichWord2VecURIFeatureUpdatePlugin].to[RichWord2VecURIFeatureUpdatePluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): LDAWordRepresenter = {
    val version = ModelVersions.denseLDAVersion
    val lda = ldaStore.get(version).get
    new LDAWordRepresenter(version, lda)
  }

  @Singleton
  @Provides
  def word2vecWordRepresenter(store: Word2VecStore): Word2VecWordRepresenter = {
    val version = ModelVersions.word2vecVersion
    val word2vec = store.get(version).get
    Word2VecWordRepresenter(version, word2vec)
  }

}
