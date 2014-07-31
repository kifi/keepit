package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.cortex.MiscPrefix
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel.{ URILDATopicRepo, UserLDAInterests, UserLDAInterestsRepo, UserTopicMean }
import com.keepit.cortex.features.Document
import com.keepit.cortex.models.lda._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.model.{ NormalizedURI, User }

import scala.collection.mutable
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
    ldaRetriever: LDAURIFeatureRetriever,
    userLDAStatsRetriever: UserLDAStatisticsRetriever) {
  assume(ldaTopicWords.topicWords.length == wordRep.lda.dimension)

  var currentConfig = ldaConfigs

  val ldaDimMap = mutable.Map(wordRep.version -> wordRep.lda.dimension)

  // consumers of lda service might query dim of some previous lda model
  def getLDADimension(version: ModelVersion[DenseLDA]): Int = {
    ldaDimMap.getOrElseUpdate(version, {
      val conf = configStore.get(MiscPrefix.LDA.topicConfigsJsonFile, version).get
      conf.configs.size
    }
    )
  }

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

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getFeature(uriId, wordRep.version)
      val userInterestOpt = userTopicRepo.getByUser(userId, wordRep.version)
      computeInterestScore(uriTopicOpt, userInterestOpt)
    }
  }

  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[LDAUserURIInterestScores] = {
    db.readOnlyReplica { implicit s =>
      val userInterestOpt = userTopicRepo.getByUser(userId, wordRep.version)
      val uriTopicOpts = uriIds.map { uriId => uriTopicRepo.getFeature(uriId, wordRep.version) }
      uriTopicOpts.map { uriTopicOpt =>
        computeInterestScore(uriTopicOpt, userInterestOpt)
      }
    }
  }

  private def computeInterestScore(uriTopicOpt: Option[LDATopicFeature], userInterestOpt: Option[UserLDAInterests]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeInterestScore(userFeat.numOfEvidence, userFeat.userTopicMean, Some(uriFeat))
        val recencyScore = computeInterestScore(userFeat.numOfRecentEvidence, userFeat.userRecentTopicMean, Some(uriFeat))
        LDAUserURIInterestScores(globalScore, recencyScore)
      case _ => LDAUserURIInterestScores(None, None)
    }
  }

  private def computeInterestScore(numOfEvidenceForUser: Int, userFeatOpt: Option[UserTopicMean], uriFeatOpt: Option[LDATopicFeature]): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeatOpt) match {
      case (Some(userFeat), Some(uriFeat)) =>
        val userVec = getUserLDAStats(wordRep.version) match {
          case None => userFeat.mean
          case Some(stat) => scale(userFeat.mean, stat.mean, stat.std)
        }
        val (u, v) = (projectToActive(userVec), projectToActive(uriFeat.value))
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

  private def scale(datum: Array[Float], mean: Array[Float], std: Array[Float]): Array[Float] = {
    assume(datum.size == mean.size && mean.size == std.size)
    (0 until datum.size).map { i =>
      if (std(i) == 0) datum(i) - mean(i) else (datum(i) - mean(i)) / std(i)
    }.toArray
  }

  private def scale(datum: Array[Float], userLDAStats: Option[UserLDAStatistics]): Array[Float] = {
    userLDAStats match {
      case Some(stat) => scale(datum, stat.mean, stat.std)
      case None => datum
    }
  }

  def getUserLDAStats(version: ModelVersion[DenseLDA]): Option[UserLDAStatistics] = {
    userLDAStatsRetriever.getUserLDAStats(version)
  }

  def getSimilarUsers(userId: Id[User], topK: Int): (Seq[Id[User]], Seq[Float]) = {
    val target = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId, wordRep.version) }
    if (target.isEmpty) return (Seq(), Seq())
    val statOpt = getUserLDAStats(wordRep.version)
    val targetScaled = scale(target.get.mean, statOpt)

    val (users, vecs) = db.readOnlyReplica { implicit s => userTopicRepo.getAllUserTopicMean(wordRep.version, minEvidence = 100) }
    val idsAndScores = (users zip vecs).map {
      case (userId, vec) =>
        val (u, v) = (scale(targetScaled, statOpt), scale(vec.mean, statOpt))
        val score = cosineDistance(u, v).toFloat
        (userId, score)
    }

    idsAndScores.sortBy(-1 * _._2).take(topK + 1).filter(_._1 != userId).unzip
  }

  def dumpScaledUserInterest(userId: Id[User]): Option[Array[Float]] = {
    val vecOpt = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId, wordRep.version) }
    vecOpt.map { vec => val statOpt = getUserLDAStats(wordRep.version); scale(vec.mean, statOpt) }
  }

}
