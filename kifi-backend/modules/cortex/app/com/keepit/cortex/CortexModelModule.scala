package com.keepit.cortex

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.cortex.article.CortexArticleProvider
import com.keepit.cortex.models.lda._
import com.keepit.cortex.models.word2vec._
import com.keepit.cortex.nlp.Stopwords
import com.keepit.cortex.tagcloud.{ TagCloudPluginImpl, TagCloudPlugin }
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.common.logging.Logging

trait CortexModelModule extends ScalaModule

case class CortexProdModelModule() extends CortexModelModule with Logging {
  def configure() {
    bind[LDADbUpdatePlugin].to[LDADbUpdatePluginImpl].in[AppScoped]
    bind[LDAUserDbUpdatePlugin].to[LDAUserDbUpdatePluginImpl].in[AppScoped]
    bind[LDAUserStatDbUpdatePlugin].to[LDAUserStatDbUpdatePluginImpl].in[AppScoped]
    bind[UserLDAStatisticsPlugin].to[UserLDAStatisticsPluginImpl].in[AppScoped]
    bind[LDAInfoUpdatePlugin].to[LDAInfoUpdatePluginImpl].in[AppScoped]
    bind[LDALibraryUpdaterPlugin].to[LDALibraryUpdaterPluginImpl].in[AppScoped]
    bind[LDARelatedLibraryPlugin].to[LDARelatedLibraryPluginImpl].in[AppScoped]
    bind[TagCloudPlugin].to[TagCloudPluginImpl].in[AppScoped]
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
  def ldaUriRepresenter(docRep: MultiVersionedLDADocRepresenter, articleProvider: CortexArticleProvider): MultiVersionedLDAURIRepresenter = {
    val uriReps = docRep.representers.map { docRep => LDAURIRepresenter(docRep, articleProvider) }
    MultiVersionedLDAURIRepresenter(uriReps: _*)
  }

}

case class CortexDevModelModule() extends CortexModelModule() {
  def configure() {
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
  def ldaUriRepresenter(docRep: MultiVersionedLDADocRepresenter, articleProvider: CortexArticleProvider): MultiVersionedLDAURIRepresenter = {
    val uriReps = docRep.representers.map { docRep => LDAURIRepresenter(docRep, articleProvider) }
    MultiVersionedLDAURIRepresenter(uriReps: _*)
  }

}
