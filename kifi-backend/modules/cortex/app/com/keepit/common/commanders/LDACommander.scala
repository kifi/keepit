package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.features.Document
import com.keepit.cortex.models.lda._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.model.{ Keep, NormalizedURI, User }
import com.keepit.common.time._
import scala.math.exp

@Singleton
class LDACommander @Inject() (
    infoCommander: LDAInfoCommander,
    db: Database,
    userTopicRepo: UserLDAInterestsRepo,
    uriTopicRepo: URILDATopicRepo,
    wordRep: LDAWordRepresenter,
    docRep: LDADocRepresenter,
    ldaRetriever: LDAURIFeatureRetriever,
    userLDAStatsRetriever: UserLDAStatisticsRetriever,
    topicInfoRepo: LDAInfoRepo,
    userLDAStatRepo: UserLDAStatsRepo) extends Logging {

  val numOfTopics: Int = wordRep.lda.dimension

  def activeTopics = infoCommander.activeTopics

  def getLDADimension(version: ModelVersion[DenseLDA]): Int = {
    infoCommander.getLDADimension(version)
  }

  private def projectToActive(arr: Array[Float]): Array[Float] = {
    assume(arr.size == numOfTopics)
    activeTopics.map { i => arr(i) }.toArray
  }

  def wordTopic(word: String): Option[Array[Float]] = {
    wordRep(word).map { _.vectorize }
  }

  def docTopic(doc: Document): Option[Array[Float]] = {
    docRep(doc).map { _.vectorize }
  }

  def getLDAFeatures(ids: Seq[Id[NormalizedURI]]) = {
    ldaRetriever.getByKeys(ids, wordRep.version)
  }

  def userTopicMean(userId: Id[User]): Option[UserLDAInterests] = {
    db.readOnlyReplica { implicit s =>
      userTopicRepo.getByUser(userId, wordRep.version)
    }
  }

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getActiveByURI(uriId, wordRep.version)
      val userInterestOpt = userTopicRepo.getByUser(userId, wordRep.version)
      computeInterestScore(uriTopicOpt, userInterestOpt)
    }
  }

  def gaussianUserUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getActiveByURI(uriId, wordRep.version)
      val userInterestStatOpt = userLDAStatRepo.getByUser(userId, wordRep.version)
      computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
    }
  }

  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[LDAUserURIInterestScores] = {
    db.readOnlyReplica { implicit s =>
      val userInterestOpt = userTopicRepo.getByUser(userId, wordRep.version)
      val uriTopicOpts = uriIds.map { uriId => uriTopicRepo.getActiveByURI(uriId, wordRep.version) }
      uriTopicOpts.map { uriTopicOpt =>
        computeInterestScore(uriTopicOpt, userInterestOpt)
      }
    }
  }

  def batchGaussianUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[LDAUserURIInterestScores] = {
    db.readOnlyReplica { implicit s =>
      val userInterestStatOpt = userLDAStatRepo.getActiveByUser(userId, wordRep.version)
      val uriTopicOpts = uriIds.map { uriId => uriTopicRepo.getActiveByURI(uriId, wordRep.version) }
      uriTopicOpts.map { uriTopicOpt =>
        computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
      }
    }
  }

  private def computeGaussianInterestScore(uriTopicOpt: Option[URILDATopic], userInterestOpt: Option[UserLDAStats]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeGaussianInterestScore(uriFeat.numOfWords, userFeat.numOfEvidence, Some(userFeat), uriFeat.feature, isRecent = false)
        LDAUserURIInterestScores(globalScore, None)
      case _ => LDAUserURIInterestScores(None, None)
    }
  }

  private def computeGaussianInterestScore(numOfWords: Int, numOfEvidenceForUser: Int, userFeatOpt: Option[UserLDAStats], uriFeatOpt: Option[LDATopicFeature], isRecent: Boolean): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeatOpt) match {
      case (Some(userFeat), Some(uriFeat)) =>
        val userMean = userFeat.userTopicMean.get.mean
        val userVar = userFeat.userTopicVar.get.value
        val s = userMean.sum
        assume(s > 0)
        val dist = weightedMDistanceDiagGaussian(uriFeat.value, userMean, userVar, userMean.map { _ / s })
        Some(LDAUserURIInterestScore(exp(-1 * dist), computeConfidence(numOfWords, numOfEvidenceForUser, isRecent)))
      case _ => None
    }
  }

  private def computeInterestScore(uriTopicOpt: Option[URILDATopic], userInterestOpt: Option[UserLDAInterests]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeInterestScore(uriFeat.numOfWords, userFeat.numOfEvidence, userFeat.userTopicMean, uriFeat.feature, isRecent = false)
        val recencyScore = computeInterestScore(uriFeat.numOfWords, userFeat.numOfRecentEvidence, userFeat.userRecentTopicMean, uriFeat.feature, isRecent = true)
        LDAUserURIInterestScores(globalScore, recencyScore)
      case _ => LDAUserURIInterestScores(None, None)
    }
  }

  private def computeInterestScore(numOfWords: Int, numOfEvidenceForUser: Int, userFeatOpt: Option[UserTopicMean], uriFeatOpt: Option[LDATopicFeature], isRecent: Boolean): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeatOpt) match {
      case (Some(userFeat), Some(uriFeat)) =>
        val userVec = getUserLDAStats(wordRep.version) match {
          case None => userFeat.mean
          case Some(stat) => scale(userFeat.mean, stat.mean, stat.std)
        }
        val (u, v) = (projectToActive(userVec), projectToActive(uriFeat.value))
        Some(LDAUserURIInterestScore(cosineDistance(u, v), computeConfidence(numOfWords, numOfEvidenceForUser, isRecent)))
      case _ => None
    }
  }

  private def computeConfidence(numOfWords: Int, numOfEvidenceForUser: Int, isRecent: Boolean) = {
    val alpha = if (isRecent) (numOfEvidenceForUser - 10) / 5f else (numOfEvidenceForUser - 30) / 10f
    val s1 = 1f / (1 + exp(-1 * alpha)).toFloat
    val beta = (numOfWords - 50) / 50f
    val s2 = 1f / (1 + exp(-1 * beta)).toFloat
    s1 * s2
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
        val (u, v) = (targetScaled, scale(vec.mean, statOpt))
        val score = cosineDistance(u, v).toFloat
        (userId, score)
    }

    idsAndScores.sortBy(-1 * _._2).take(topK + 1).filter(_._1 != userId).unzip
  }

  def dumpScaledUserInterest(userId: Id[User]): Option[Array[Float]] = {
    val vecOpt = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId, wordRep.version) }
    vecOpt.map { vec => val statOpt = getUserLDAStats(wordRep.version); scale(vec.mean, statOpt) }
  }

  def userSimilairty(userId1: Id[User], userId2: Id[User]): Option[Float] = {
    val vecOpt1 = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId1, wordRep.version) }
    val vecOpt2 = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId2, wordRep.version) }
    val statOpt = getUserLDAStats(wordRep.version)

    (vecOpt1, vecOpt2) match {
      case (Some(v1), Some(v2)) => Some(cosineDistance(scale(v1.mean, statOpt), scale(v2.mean, statOpt)))
      case _ => None
    }

  }

  def getTopicNames(uris: Seq[Id[NormalizedURI]]): Seq[Option[String]] = {
    val cutoff = (1.0f / numOfTopics) * 50
    val topicIdOpts = db.readOnlyReplica { implicit s =>
      uris.map { uri =>
        uriTopicRepo.getFirstTopicAndScore(uri, wordRep.version) match {
          case Some((topic, score)) =>
            if (score > cutoff) Some(topic.index) else None
          case None => None
        }
      }
    }

    topicIdOpts.map { idOpt =>
      idOpt match {
        case Some(id) => infoCommander.getTopicName(id)
        case None => None
      }
    }
  }

  def explainFeed(userId: Id[User], uris: Seq[Id[NormalizedURI]]): Seq[Seq[Id[Keep]]] = {

    val MAX_KL_DIST = 0.8f // empirically this should be < 1.0
    val topK = 3

    def bestMatch(userFeats: Seq[(Id[Keep], LDATopicFeature)], uriFeat: URILDATopic): Seq[Id[Keep]] = {
      val scored = userFeats.map {
        case (kid, ufeat) =>
          val score = KL_divergence(ufeat.value, uriFeat.feature.get.value)
          (kid, score)
      }
      scored.filter { _._2 < MAX_KL_DIST }.sortBy(_._2).take(topK).map { _._1 }
    }

    val userFeats = db.readOnlyReplica { implicit s => uriTopicRepo.getUserRecentURIFeatures(userId, wordRep.version, min_num_words = 50, limit = 50) }
    val uriFeats = db.readOnlyReplica { implicit s => uris.map { uri => uriTopicRepo.getActiveByURI(uri, wordRep.version) } }
    uriFeats.map { uriFeatOpt =>
      uriFeatOpt match {
        case Some(uriFeat) if uriFeat.numOfWords > 50 => bestMatch(userFeats, uriFeat)
        case _ => Seq()
      }
    }
  }

  def uriKLDivergence(uriId1: Id[NormalizedURI], uriId2: Id[NormalizedURI]): Option[Float] = {
    val feat1 = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId1, wordRep.version) }
    val feat2 = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId2, wordRep.version) }
    (feat1, feat2) match {
      case (Some(f1), Some(f2)) if (f1.numOfWords > 50 && f2.numOfWords > 50) => Some(KL_divergence(f1.feature.get.value, f2.feature.get.value))
      case _ => None
    }
  }

}
