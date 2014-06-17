package com.keepit.common.commanders

import com.google.inject.{Inject, Singleton}
import com.keepit.cortex.models.lda._
import com.keepit.cortex.features.Document
import com.keepit.cortex.MiscPrefix
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI


@Singleton
class LDACommander @Inject()(
  wordRep: LDAWordRepresenter,
  docRep: LDADocRepresenter,
  ldaTopicWords: DenseLDATopicWords,
  ldaConfigs: LDATopicConfigurations,
  configStore: LDAConfigStore,
  ldaRetriever: LDAURIFeatureRetriever
){
  assume(ldaTopicWords.topicWords.length == wordRep.lda.dimension)

  var currentConfig = ldaConfigs

  def numOfTopics: Int = ldaTopicWords.topicWords.length

  def topicWords(topicId: Int, topN: Int): Seq[(String, Float)] = {
    assume(topicId >=0 && topicId < numOfTopics && topN >= 0)

    ldaTopicWords.topicWords(topicId).toArray.sortBy(-1f * _._2).take(topN)
  }

  def topicWords(fromId: Int, toId: Int, topN: Int): Map[Int, Seq[(String, Float)]] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)

    (fromId to toId).map{ id =>
      (id, topicWords(id, topN))
    }.toMap
  }

  def topicConfigs(fromId: Int, toId: Int): Map[String, LDATopicConfiguration] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)
    (fromId to toId).map{ id =>
      id.toString -> currentConfig.configs.getOrElse(id.toString, LDATopicConfiguration.default)
    }.toMap
  }

  def wordTopic(word: String): Option[Array[Float]] = {
    wordRep(word).map{_.vectorize}
  }

  def docTopic(doc: Document): Option[Array[Float]] = {
    docRep(doc).map{_.vectorize}
  }

  def saveConfigEdits(config: Map[String, LDATopicConfiguration]) = {
    val newConfig = LDATopicConfigurations(currentConfig.configs ++ config)
    currentConfig = newConfig
    configStore.+= (MiscPrefix.LDA.topicConfigsJsonFile, wordRep.version, newConfig)
  }

  def getLDAFeatures(ids: Seq[Id[NormalizedURI]]) = {
    ldaRetriever.getByKeys(ids, wordRep.version)
  }
}
