package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.cortex.dbmodel.{ URILDATopicRepo, UserTopicMean, UserLDAInterestsRepo }
import com.keepit.cortex.models.lda._
import com.keepit.cortex.features.Document
import com.keepit.cortex.MiscPrefix
import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.cortex.utils.MatrixUtils.cosineDistance
import scala.math.exp

@Singleton
class LDACommander @Inject() (
    db: Database,
    userTopicRepo: UserLDAInterestsRepo,
    uriTopicRepo: URILDATopicRepo,
    wordRep: LDAWordRepresenter,
    docRep: LDADocRepresenter,
    ldaTopicWords: DenseLDATopicWords,
    ldaConfigs: LDATopicConfigurations,
    configStore: LDAConfigStore,
    ldaRetriever: LDAURIFeatureRetriever) {
  assume(ldaTopicWords.topicWords.length == wordRep.lda.dimension)

  var currentConfig = ldaConfigs

  def numOfTopics: Int = ldaTopicWords.topicWords.length

  val activeTopics = currentConfig.configs.filter { case (id, conf) => conf.isActive }.map { case (id, _) => id.toInt }.toArray.sorted

  private def projectToActive(arr: Array[Float]): Array[Float] = {
    assume(arr.size == numOfTopics)
    activeTopics.map { i => arr(i) }.toArray
  }

  def topicWords(topicId: Int, topN: Int): Seq[(String, Float)] = {
    assume(topicId >= 0 && topicId < numOfTopics && topN >= 0)

    ldaTopicWords.topicWords(topicId).toArray.sortBy(-1f * _._2).take(topN)
  }

  def topicWords(fromId: Int, toId: Int, topN: Int): Map[Int, Seq[(String, Float)]] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)

    (fromId to toId).map { id =>
      (id, topicWords(id, topN))
    }.toMap
  }

  def topicConfigs(fromId: Int, toId: Int): Map[String, LDATopicConfiguration] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)
    (fromId to toId).map { id =>
      id.toString -> currentConfig.configs.getOrElse(id.toString, LDATopicConfiguration.default)
    }.toMap
  }

  def wordTopic(word: String): Option[Array[Float]] = {
    wordRep(word).map { _.vectorize }
  }

  def docTopic(doc: Document): Option[Array[Float]] = {
    docRep(doc).map { _.vectorize }
  }

  def ldaConfigurations: LDATopicConfigurations = currentConfig

  def saveConfigEdits(config: Map[String, LDATopicConfiguration]) = {
    val newConfig = LDATopicConfigurations(currentConfig.configs ++ config)
    currentConfig = newConfig
    configStore.+=(MiscPrefix.LDA.topicConfigsJsonFile, wordRep.version, newConfig)
  }

  def getLDAFeatures(ids: Seq[Id[NormalizedURI]]) = {
    ldaRetriever.getByKeys(ids, wordRep.version)
  }

  def userTopicMean(userId: Id[User]): Option[UserTopicMean] = {
    db.readOnlyReplica { implicit s =>
      userTopicRepo.getTopicMeanByUser(userId, wordRep.version)
    }
  }

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): (Option[LDAUserURIInterestScore], Option[LDAUserURIInterestScore]) = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getFeature(uriId, wordRep.version)
      val userInterestOpt = userTopicRepo.getByUser(userId, wordRep.version)
      (uriTopicOpt, userInterestOpt) match {
        case (Some(uriFeat), Some(userFeat)) =>
          val globalScore = computeInterestScore(userFeat.numOfEvidence, userFeat.userTopicMean, Some(uriFeat))
          val recencyScore = computeInterestScore(userFeat.numOfRecentEvidence, userFeat.userRecentTopicMean, Some(uriFeat))
          (globalScore, recencyScore)
        case _ => (None, None)
      }
    }
  }

  private def computeInterestScore(numOfEvidenceForUser: Int, userFeatOpt: Option[UserTopicMean], uriFeatOpt: Option[LDATopicFeature]): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeatOpt) match {
      case (Some(userFeat), Some(uriFeat)) =>
        val (u, v) = (projectToActive(userFeat.mean), projectToActive(uriFeat.value))
        Some(LDAUserURIInterestScore(cosineDistance(u, v), computeConfidence(numOfEvidenceForUser)))
      case _ => None
    }
  }

  private def computeConfidence(numOfEvidenceForUser: Int) = {
    val alpha = (numOfEvidenceForUser - 30) / 10f
    1f / (1 + exp(-1 * alpha)).toFloat
  }

  def sampleURIs(topicId: Int): Seq[Id[NormalizedURI]] = {
    val SAMPLE_SIZE = 20
    val uris = db.readOnlyReplica { implicit s =>
      uriTopicRepo.getLatestURIsInTopic(LDATopic(topicId), wordRep.version, limit = 100)
    }
    scala.util.Random.shuffle(uris).take(SAMPLE_SIZE)
  }
}
