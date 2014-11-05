package com.keepit.cortex

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.cortex.models.lda._
import com.keepit.cortex.models.word2vec._
import com.keepit.cortex.nlp.Stopwords
import com.keepit.search.ArticleStore
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.common.logging.Logging

trait CortexModelModule extends ScalaModule

case class CortexProdModelModule() extends CortexModelModule with Logging {
  def configure() {
    bind[RichWord2VecURIFeatureUpdatePlugin].to[RichWord2VecURIFeatureUpdatePluginImpl].in[AppScoped]
    bind[LDADbUpdatePlugin].to[LDADbUpdatePluginImpl].in[AppScoped]
    bind[LDAUserDbUpdatePlugin].to[LDAUserDbUpdatePluginImpl].in[AppScoped]
    bind[LDAUserStatDbUpdatePlugin].to[LDAUserStatDbUpdatePluginImpl].in[AppScoped]
    bind[UserLDAStatisticsPlugin].to[UserLDAStatisticsPluginImpl].in[AppScoped]
    bind[LDAInfoUpdatePlugin].to[LDAInfoUpdatePluginImpl].in[AppScoped]
    bind[LDALibraryUpdaterPlugin].to[LDALibraryUpdaterPluginImpl].in[AppScoped]
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
  def ldaDocRepresenter(wordRep: LDAWordRepresenter, stopWords: Stopwords): LDADocRepresenter = {
    LDADocRepresenter(wordRep, stopWords)
  }

  @Singleton
  @Provides
  def ldaUriRepresenter(docRep: LDADocRepresenter, articleStore: ArticleStore): MultiVersionedLDAURIRepresenter = {
    val uriRep = LDAURIRepresenter(docRep, articleStore)
    MultiVersionedLDAURIRepresenter(uriRep)
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

case class CortexDevModelModule() extends CortexModelModule() {
  def configure() {
    bind[RichWord2VecURIFeatureUpdatePlugin].to[RichWord2VecURIFeatureUpdatePluginImpl].in[AppScoped]
    bind[LDADbUpdatePlugin].to[LDADbUpdatePluginImpl].in[AppScoped]
    bind[LDAUserDbUpdatePlugin].to[LDAUserDbUpdatePluginImpl].in[AppScoped]
    bind[LDAUserStatDbUpdatePlugin].to[LDAUserStatDbUpdatePluginImpl].in[AppScoped]
    bind[UserLDAStatisticsPlugin].to[UserLDAStatisticsPluginImpl].in[AppScoped]
    bind[LDAInfoUpdatePlugin].to[LDAInfoUpdatePluginImpl].in[AppScoped]
    bind[LDALibraryUpdaterPlugin].to[LDALibraryUpdaterPluginImpl].in[AppScoped]
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
  def ldaDocRepresenter(wordRep: LDAWordRepresenter, stopWords: Stopwords): LDADocRepresenter = {
    LDADocRepresenter(wordRep, stopWords)
  }

  @Singleton
  @Provides
  def ldaUriRepresenter(docRep: LDADocRepresenter, articleStore: ArticleStore): MultiVersionedLDAURIRepresenter = {
    val uriRep = LDAURIRepresenter(docRep, articleStore)
    MultiVersionedLDAURIRepresenter(uriRep)
  }

  @Singleton
  @Provides
  def word2vecWordRepresenter(store: Word2VecStore): Word2VecWordRepresenter = {
    val version = ModelVersions.word2vecVersion
    val word2vec = store.get(version).get
    Word2VecWordRepresenter(version, word2vec)
  }

}
