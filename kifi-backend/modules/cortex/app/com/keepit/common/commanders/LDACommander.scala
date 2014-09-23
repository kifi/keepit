package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.models.lda._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.cortex.PublishingVersions
import com.keepit.model.{ Library, Keep, NormalizedURI, User }
import play.api.libs.json._
import scala.math.exp

@Singleton
class LDACommander @Inject() (
    infoCommander: LDAInfoCommander,
    db: Database,
    userTopicRepo: UserLDAInterestsRepo,
    uriTopicRepo: URILDATopicRepo,
    userLDAStatsRetriever: UserLDAStatisticsRetriever,
    topicInfoRepo: LDAInfoRepo,
    userLDAStatRepo: UserLDAStatsRepo,
    libTopicRepo: LibraryLDATopicRepo,
    userStatUpdatePlugin: LDAUserStatDbUpdatePlugin) extends Logging {

  val ldaVersion = PublishingVersions.denseLDAVersion // all methods in this commander should query against this version
  assume(ldaVersion == infoCommander.ldaVersion)

  val numOfTopics: Int = infoCommander.getLDADimension()

  def activeTopics = infoCommander.activeTopics

  private def projectToActive(arr: Array[Float]): Array[Float] = {
    assume(arr.size == numOfTopics)
    activeTopics.map { i => arr(i) }.toArray
  }

  def userTopicMean(userId: Id[User]): Option[UserLDAInterests] = {
    db.readOnlyReplica { implicit s =>
      userTopicRepo.getByUser(userId, ldaVersion)
    }
  }

  def libraryTopic(libId: Id[Library]): Option[LibraryLDATopic] = {
    db.readOnlyReplica { implicit s => libTopicRepo.getActiveByLibraryId(libId, ldaVersion) }
  }

  // for admin
  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getActiveByURI(uriId, ldaVersion)
      val userInterestOpt = userTopicRepo.getByUser(userId, ldaVersion)
      computeCosineInterestScore(uriTopicOpt, userInterestOpt)
    }
  }

  // for admin
  def gaussianUserUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getActiveByURI(uriId, ldaVersion)
      val userInterestStatOpt = userLDAStatRepo.getByUser(userId, ldaVersion)
      computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
    }
  }

  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[LDAUserURIInterestScores] = {

    def isInJunkTopic(uriTopicOpt: Option[URILDATopic], junks: Set[Int]): Boolean = uriTopicOpt.exists(x => x.firstTopic.exists(t => junks.contains(t.index)))

    val junkTopics = infoCommander.inactiveTopics
    db.readOnlyReplica { implicit s =>
      val userInterestOpt = userTopicRepo.getByUser(userId, ldaVersion)
      val userInterestStatOpt = userLDAStatRepo.getActiveByUser(userId, ldaVersion)
      val libFeats = db.readOnlyReplica { implicit s => libTopicRepo.getUserFollowedLibraryFeatures(userId, ldaVersion) }
      val uriTopicOpts = uriTopicRepo.getActiveByURIs(uriIds, ldaVersion)
      uriTopicOpts.map { uriTopicOpt =>
        if (!isInJunkTopic(uriTopicOpt, junkTopics)) {
          val s1 = computeCosineInterestScore(uriTopicOpt, userInterestOpt)
          val s2 = computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
          val s3 = libraryInducedUserURIInterestScore(libFeats, uriTopicOpt)
          LDAUserURIInterestScores(s2.global, s1.recency, s3)
        } else {
          log.info("uri in junk topic. return zero scores for user")
          LDAUserURIInterestScores(None, None, None)
        }
      }
    }
  }

  // admin
  def libraryInducedUserURIInterest(userId: Id[User], uriId: Id[NormalizedURI]): Option[LDAUserURIInterestScore] = {
    val libFeats = db.readOnlyReplica { implicit s => libTopicRepo.getUserFollowedLibraryFeatures(userId, ldaVersion) }
    val uriFeatOpt = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId, ldaVersion) }
    libraryInducedUserURIInterestScore(libFeats, uriFeatOpt)
  }

  private def libraryInducedUserURIInterestScore(libFeats: Seq[LibraryTopicMean], uriLDAOpt: Option[URILDATopic]): Option[LDAUserURIInterestScore] = {
    val uriFeatOpt = uriLDAOpt.flatMap(_.feature)
    val numWords = uriLDAOpt.map { _.numOfWords }.getOrElse(0)
    val numTopicChanges = uriLDAOpt.map { _.timesFirstTopicChanged }.getOrElse(0)
    if (libFeats.size > 0 && uriFeatOpt.isDefined) {
      val uriFeat = uriFeatOpt.get.value
      val libsFeats = libFeats.map { _.value }
      val div = libsFeats.map { v => KL_divergence(v, uriFeat) }.min
      val score = (1f - div) max 0f
      val confidence = computeURIConfidence(numWords, numTopicChanges)
      Some(LDAUserURIInterestScore(score, confidence))
    } else None
  }

  private def computeGaussianInterestScore(uriTopicOpt: Option[URILDATopic], userInterestOpt: Option[UserLDAStats]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeGaussianInterestScore(userFeat.numOfEvidence, Some(userFeat), uriFeat, isRecent = false)
        LDAUserURIInterestScores(globalScore, None, None)
      case _ => LDAUserURIInterestScores(None, None, None)
    }
  }

  private def computeGaussianInterestScore(numOfEvidenceForUser: Int, userFeatOpt: Option[UserLDAStats], uriFeat: URILDATopic, isRecent: Boolean): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeat.feature) match {
      case (Some(userFeat), Some(uriFeatVec)) =>
        val userMean = projectToActive(userFeat.userTopicMean.get.mean)
        val userVar = projectToActive(userFeat.userTopicVar.get.value)
        val s = userMean.sum
        assume(s > 0)
        val dist = weightedMDistanceDiagGaussian(projectToActive(uriFeatVec.value), userMean, userVar, userMean.map { _ / s })
        val confidence = computeURIConfidence(uriFeat.numOfWords, uriFeat.timesFirstTopicChanged)
        Some(LDAUserURIInterestScore(exp(-1 * dist), confidence))
      case _ => None
    }
  }

  private def computeCosineInterestScore(uriTopicOpt: Option[URILDATopic], userInterestOpt: Option[UserLDAInterests]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeCosineInterestScore(userFeat.numOfEvidence, userFeat.userTopicMean, uriFeat, isRecent = false)
        val recencyScore = computeCosineInterestScore(userFeat.numOfRecentEvidence, userFeat.userRecentTopicMean, uriFeat, isRecent = true)
        LDAUserURIInterestScores(globalScore, recencyScore, None)
      case _ => LDAUserURIInterestScores(None, None, None)
    }
  }

  private def computeCosineInterestScore(numOfEvidenceForUser: Int, userFeatOpt: Option[UserTopicMean], uriFeat: URILDATopic, isRecent: Boolean): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeat.feature) match {
      case (Some(userFeat), Some(uriFeatVec)) =>
        val userVec = getUserLDAStats(ldaVersion) match {
          case None => userFeat.mean
          case Some(stat) => scale(userFeat.mean, stat.mean, stat.std)
        }
        val (u, v) = (projectToActive(userVec), projectToActive(uriFeatVec.value))
        val confidence = computeURIConfidence(uriFeat.numOfWords, uriFeat.timesFirstTopicChanged)
        Some(LDAUserURIInterestScore(cosineDistance(u, v), confidence))
      case _ => None
    }
  }

  private def computeURIConfidence(numOfWords: Int, numTopicChanges: Int) = {
    val alpha = numTopicChanges / 10f
    val penalty = exp(-alpha * alpha)

    val beta = (numOfWords - 50) / 50f
    val score = 1f / (1 + exp(-1 * beta)).toFloat

    score * penalty
  }

  def sampleURIs(topicId: Int): Seq[(Id[NormalizedURI], Float)] = {
    val SAMPLE_SIZE = 20
    val uris = db.readOnlyReplica { implicit s =>
      uriTopicRepo.getLatestURIsInTopic(LDATopic(topicId), ldaVersion, limit = 100)
    }
    scala.util.Random.shuffle(uris).take(SAMPLE_SIZE).sortBy(-1f * _._2)
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
    val target = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId, ldaVersion) }
    if (target.isEmpty) return (Seq(), Seq())
    val statOpt = getUserLDAStats(ldaVersion)
    val targetScaled = scale(target.get.mean, statOpt)

    val (users, vecs) = db.readOnlyReplica { implicit s => userTopicRepo.getAllUserTopicMean(ldaVersion, minEvidence = 100) }
    val idsAndScores = (users zip vecs).map {
      case (userId, vec) =>
        val (u, v) = (targetScaled, scale(vec.mean, statOpt))
        val score = cosineDistance(u, v).toFloat
        (userId, score)
    }

    idsAndScores.sortBy(-1 * _._2).take(topK + 1).filter(_._1 != userId).unzip
  }

  def userSimilairty(userId1: Id[User], userId2: Id[User]): Option[Float] = {
    val vecOpt1 = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId1, ldaVersion) }
    val vecOpt2 = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId2, ldaVersion) }
    val statOpt = getUserLDAStats(ldaVersion)

    (vecOpt1, vecOpt2) match {
      case (Some(v1), Some(v2)) => Some(cosineDistance(scale(v1.mean, statOpt), scale(v2.mean, statOpt)))
      case _ => None
    }

  }

  def getTopicNames(uris: Seq[Id[NormalizedURI]]): Seq[Option[String]] = {
    val cutoff = (1.0f / numOfTopics) * 50
    val topicIdOpts = db.readOnlyReplica { implicit s =>
      uris.map { uri =>
        uriTopicRepo.getFirstTopicAndScore(uri, ldaVersion) match {
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

    val MAX_KL_DIST = 0.5f // empirically this should be < 1.0
    val topK = 3

    def bestMatch(userFeats: Seq[(Id[Keep], LDATopicFeature)], uriFeat: URILDATopic): Seq[Id[Keep]] = {
      val scored = userFeats.map {
        case (kid, ufeat) =>
          val score = KL_divergence(ufeat.value, uriFeat.feature.get.value)
          (kid, score)
      }
      scored.filter { _._2 < MAX_KL_DIST }.sortBy(_._2).take(topK).map { _._1 }
    }

    val userFeats = db.readOnlyReplica { implicit s => uriTopicRepo.getUserRecentURIFeatures(userId, ldaVersion, min_num_words = 50, limit = 200) }
    val uriFeats = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURIs(uris, ldaVersion) }
    uriFeats.map { uriFeatOpt =>
      uriFeatOpt match {
        case Some(uriFeat) if uriFeat.numOfWords > 50 => bestMatch(userFeats, uriFeat)
        case _ => Seq()
      }
    }
  }

  def uriKLDivergence(uriId1: Id[NormalizedURI], uriId2: Id[NormalizedURI]): Option[Float] = {
    val feat1 = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId1, ldaVersion) }
    val feat2 = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId2, ldaVersion) }
    (feat1, feat2) match {
      case (Some(f1), Some(f2)) if (f1.numOfWords > 50 && f2.numOfWords > 50) => Some(KL_divergence(f1.feature.get.value, f2.feature.get.value))
      case _ => None
    }
  }

  def recomputeUserLDAStats(): Unit = {
    val users = db.readOnlyReplica { implicit s => userLDAStatRepo.getAllUsers(ldaVersion) }
    log.info(s"recomputing user LDA Stats for ${users.size} users")
    var n = 0
    users.foreach { user =>
      userStatUpdatePlugin.updateUser(user)
      n += 1
      if (n % 100 == 0) log.info(s"recomputed ${n} user LDA Stats")
    }
  }

  // just for examine data
  def dumpFeature(dataType: String, id: Long): JsValue = {
    val version = ldaVersion
    dataType match {
      case "user" => db.readOnlyReplica { implicit s =>
        val uid = Id[User](id)
        val model = userTopicRepo.getByUser(uid, version)
        val statOpt = getUserLDAStats(version)
        val v1 = model.flatMap { _.userTopicMean.map(_.mean) }
        val v2 = model.flatMap { _.userRecentTopicMean.map(_.mean) }
        val v3 = model.flatMap(_.userTopicMean.map { _.mean }.map { v => scale(v, statOpt) })
        val v4 = model.flatMap(_.userRecentTopicMean.map { _.mean }.map { v => scale(v, statOpt) })
        Json.obj("userGlobal" -> v1, "userRecent" -> v2, "userGlobalScaled" -> v3, "userRecentScaled" -> v4)
      }

      case "uri" => db.readOnlyReplica { implicit s =>
        val uriId = Id[NormalizedURI](id)
        val model = uriTopicRepo.getActiveByURI(uriId, version)
        val v = model.flatMap(_.feature.map { _.value })
        Json.obj("uriFeat" -> v)
      }

      case "library" => db.readOnlyReplica { implicit s =>
        val libId = Id[Library](id)
        val model = libTopicRepo.getActiveByLibraryId(libId, version)
        val v = model.flatMap(_.topic.map(_.value))
        Json.obj("libFeat" -> v)
      }

      case _ => JsString("unknown data type")
    }
  }

}
