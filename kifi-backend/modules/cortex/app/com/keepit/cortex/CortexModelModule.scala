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
    bind[LDARelatedLibraryPlugin].to[LDARelatedLibraryPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): MultiVersionedLDAWordRepresenter = {
    val wordReps = ModelVersions.availableLDAVersions.map { version =>
      val lda = ldaStore.syncGet(version).get
      LDAWordRepresenter(version, lda)
    }
    MultiVersionedLDAWordRepresenter(wordReps: _*)
  }

  @Singleton
  @Provides
  def ldaDocRepresenter(wordRep: MultiVersionedLDAWordRepresenter, stopWords: Stopwords): MultiVersionedLDADocRepresenter = {
    val docReps = wordRep.representers.map { wordRep => LDADocRepresenter(wordRep, stopWords) }
    MultiVersionedLDADocRepresenter(docReps: _*)
  }

  @Singleton
  @Provides
  def ldaUriRepresenter(docRep: MultiVersionedLDADocRepresenter, articleStore: ArticleStore): MultiVersionedLDAURIRepresenter = {
    val uriReps = docRep.representers.map { docRep => LDAURIRepresenter(docRep, articleStore) }
    MultiVersionedLDAURIRepresenter(uriReps: _*)
  }

  @Singleton
  @Provides
  def word2vecWordRepresenter(store: Word2VecStore): Word2VecWordRepresenter = {
    log.info("loading word2vec from model store")
    val version = ModelVersions.word2vecVersion
    val word2vec = store.syncGet(version).get
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
    bind[LDARelatedLibraryPlugin].to[LDARelatedLibraryPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def ldaWordRepresenter(ldaStore: LDAModelStore): MultiVersionedLDAWordRepresenter = {
    val wordReps = ModelVersions.availableLDAVersions.map { version =>
      val lda = ldaStore.syncGet(version).get
      LDAWordRepresenter(version, lda)
    }
    MultiVersionedLDAWordRepresenter(wordReps: _*)
  }

  @Singleton
  @Provides
  def ldaDocRepresenter(wordRep: MultiVersionedLDAWordRepresenter, stopWords: Stopwords): MultiVersionedLDADocRepresenter = {
    val docReps = wordRep.representers.map { wordRep => LDADocRepresenter(wordRep, stopWords) }
    MultiVersionedLDADocRepresenter(docReps: _*)
  }

  @Singleton
  @Provides
  def ldaUriRepresenter(docRep: MultiVersionedLDADocRepresenter, articleStore: ArticleStore): MultiVersionedLDAURIRepresenter = {
    val uriReps = docRep.representers.map { docRep => LDAURIRepresenter(docRep, articleStore) }
    MultiVersionedLDAURIRepresenter(uriReps: _*)
  }

  @Singleton
  @Provides
  def word2vecWordRepresenter(store: Word2VecStore): Word2VecWordRepresenter = {
    val version = ModelVersions.word2vecVersion
    val word2vec = store.syncGet(version).get
    Word2VecWordRepresenter(version, word2vec)
  }

}
