package com.keepit.common.commanders

import java.util.BitSet

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.models.lda._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.model.{ Library, Keep, NormalizedURI, User }
import play.api.libs.json._
import scala.math.exp
import scala.util.Random

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

  def numOfTopics(implicit version: ModelVersion[DenseLDA]): Int = infoCommander.getLDADimension

  val activeTopics = infoCommander.activeTopics

  private def projectToActive(arr: Array[Float])(implicit version: ModelVersion[DenseLDA]): Array[Float] = {
    assume(arr.size == numOfTopics)
    activeTopics(version).map { i => arr(i) }.toArray
  }

  def userTopicMean(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Option[UserLDAInterests] = {
    db.readOnlyReplica { implicit s =>
      userTopicRepo.getByUser(userId, version)
    }
  }

  def libraryTopic(libId: Id[Library])(implicit version: ModelVersion[DenseLDA]): Option[LibraryLDATopic] = {
    db.readOnlyReplica { implicit s => libTopicRepo.getActiveByLibraryId(libId, version) }
  }

  // for admin
  def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: ModelVersion[DenseLDA]): Option[Float] = {
    userLibrariesScores(userId, libId :: Nil)(version).headOption.flatMap(identity)
  }

  def userLibrariesScores(userId: Id[User], libIds: Seq[Id[Library]])(implicit version: ModelVersion[DenseLDA]): Seq[Option[Float]] = {
    val libsTopics: Map[Id[Library], Option[LibraryLDATopic]] = db.readOnlyReplica { implicit s =>
      libTopicRepo.getActiveByLibraryIds(libIds, version)
    }.map { topic => (topic.libraryId, Some(topic)) }.toMap

    val userFeatOpt = db.readOnlyReplica { implicit s => userTopicRepo.getByUser(userId, version) }
    libIds map { libId =>
      val libFeat = libsTopics.getOrElse(libId, None)
      val libVec = libFeat.flatMap(_.topic).map(_.value)
      val userVec = userFeatOpt.flatMap(_.userTopicMean).map(_.mean)
      (libVec, userVec) match {
        case (Some(u), Some(v)) => Some(cosineDistance(u, v).toFloat)
        case _ => None
      }
    }
  }

  // for admin
  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getActiveByURI(uriId, version)
      val userInterestOpt = userTopicRepo.getByUser(userId, version)
      computeCosineInterestScore(uriTopicOpt, userInterestOpt)
    }
  }

  // for admin
  def gaussianUserUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): LDAUserURIInterestScores = {
    db.readOnlyReplica { implicit s =>
      val uriTopicOpt = uriTopicRepo.getActiveByURI(uriId, version)
      val userInterestStatOpt = userLDAStatRepo.getByUser(userId, version)
      computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
    }
  }

  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Seq[LDAUserURIInterestScores] = {

    def isInJunkTopic(uriTopicOpt: Option[URILDATopic], junks: Set[Int]): Boolean = uriTopicOpt.exists(x => x.firstTopic.exists(t => junks.contains(t.index)))

    val junkTopics = infoCommander.inactiveTopics(version)
    db.readOnlyReplica { implicit s =>
      val userInterestOpt = userTopicRepo.getByUser(userId, version)
      val userInterestStatOpt = userLDAStatRepo.getActiveByUser(userId, version)
      val libFeats = db.readOnlyReplica { implicit s => libTopicRepo.getUserFollowedLibraryFeatures(userId, version) }
      val uriTopicOpts = uriTopicRepo.getActiveByURIs(uriIds, version)
      uriTopicOpts.map { uriTopicOpt =>
        if (!isInJunkTopic(uriTopicOpt, junkTopics)) {
          val s1 = computeCosineInterestScore(uriTopicOpt, userInterestOpt)
          val s2 = computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
          val s3 = libraryInducedUserURIInterestScore(libFeats, uriTopicOpt)
          LDAUserURIInterestScores(s2.global, s1.recency, s3,
            uriTopicOpt.flatMap(_.firstTopic), uriTopicOpt.flatMap(_.secondTopic))
        } else {
          LDAUserURIInterestScores(None, None, None)
        }
      }
    }
  }

  // admin
  def libraryInducedUserURIInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): Option[LDAUserURIInterestScore] = {
    val libFeats = db.readOnlyReplica { implicit s => libTopicRepo.getUserFollowedLibraryFeatures(userId, version) }
    val uriFeatOpt = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId, version) }
    libraryInducedUserURIInterestScore(libFeats, uriFeatOpt)
  }

  private def libraryInducedUserURIInterestScore(libFeats: Seq[LibraryTopicMean], uriLDAOpt: Option[URILDATopic])(implicit version: ModelVersion[DenseLDA]): Option[LDAUserURIInterestScore] = {
    val uriFeatOpt = uriLDAOpt.flatMap(_.feature)
    val numWords = uriLDAOpt.map { _.numOfWords }.getOrElse(0)
    val numTopicChanges = uriLDAOpt.map { _.timesFirstTopicChanged }.getOrElse(0)
    if (!libFeats.isEmpty && uriFeatOpt.isDefined) {
      val uriFeat = uriFeatOpt.get.value
      val libsFeats = libFeats.map { _.value }
      val div = libsFeats.map { v => KL_divergence(v, uriFeat) }.min
      val score = (1f - div) max 0f
      val confidence = computeURIConfidence(numWords, numTopicChanges)
      Some(LDAUserURIInterestScore(score, confidence))
    } else None
  }

  private def computeGaussianInterestScore(uriTopicOpt: Option[URILDATopic], userInterestOpt: Option[UserLDAStats])(implicit version: ModelVersion[DenseLDA]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeGaussianInterestScore(userFeat.numOfEvidence, Some(userFeat), uriFeat, isRecent = false)
        LDAUserURIInterestScores(globalScore, None, None)
      case _ => LDAUserURIInterestScores(None, None, None)
    }
  }

  private def computeGaussianInterestScore(numOfEvidenceForUser: Int, userFeatOpt: Option[UserLDAStats], uriFeat: URILDATopic, isRecent: Boolean)(implicit version: ModelVersion[DenseLDA]): Option[LDAUserURIInterestScore] = {
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

  private def computeCosineInterestScore(uriTopicOpt: Option[URILDATopic], userInterestOpt: Option[UserLDAInterests], shouldScale: Boolean = false)(implicit version: ModelVersion[DenseLDA]): LDAUserURIInterestScores = {
    (uriTopicOpt, userInterestOpt) match {
      case (Some(uriFeat), Some(userFeat)) =>
        val globalScore = computeCosineInterestScore(userFeat.numOfEvidence, userFeat.userTopicMean, uriFeat, isRecent = false, shouldScale = shouldScale)
        val recencyScore = computeCosineInterestScore(userFeat.numOfRecentEvidence, userFeat.userRecentTopicMean, uriFeat, isRecent = true, shouldScale = shouldScale)
        LDAUserURIInterestScores(globalScore, recencyScore, None)
      case _ => LDAUserURIInterestScores(None, None, None)
    }
  }

  private def computeCosineInterestScore(numOfEvidenceForUser: Int, userFeatOpt: Option[UserTopicMean], uriFeat: URILDATopic, isRecent: Boolean, shouldScale: Boolean)(implicit version: ModelVersion[DenseLDA]): Option[LDAUserURIInterestScore] = {
    (userFeatOpt, uriFeat.feature) match {
      case (Some(userFeat), Some(uriFeatVec)) =>
        val userVec = getUserLDAStats(version) match {
          case Some(stat) if shouldScale => scale(userFeat.mean, stat.mean, stat.std)
          case _ => userFeat.mean
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

  def sampleURIs(topicId: Int)(implicit version: ModelVersion[DenseLDA]): Seq[(Id[NormalizedURI], Float)] = {
    val SAMPLE_SIZE = 20
    val uris = db.readOnlyReplica { implicit s =>
      uriTopicRepo.getLatestURIsInTopic(LDATopic(topicId), version, limit = 100)
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

  def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: ModelVersion[DenseLDA]): (Seq[Id[User]], Seq[Float]) = {
    val target = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId, version) }
    if (target.isEmpty) return (Seq(), Seq())
    val statOpt = getUserLDAStats(version)
    val targetScaled = scale(target.get.mean, statOpt)

    val (users, vecs) = db.readOnlyReplica { implicit s => userTopicRepo.getAllUserTopicMean(version, minEvidence = 100) }
    val idsAndScores = (users zip vecs).map {
      case (userId, vec) =>
        val (u, v) = (targetScaled, scale(vec.mean, statOpt))
        val score = cosineDistance(u, v).toFloat
        (userId, score)
    }

    idsAndScores.sortBy(-1 * _._2).take(topK + 1).filter(_._1 != userId).unzip
  }

  def userSimilairty(userId1: Id[User], userId2: Id[User])(implicit version: ModelVersion[DenseLDA]): Option[Float] = {
    val vecOpt1 = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId1, version) }
    val vecOpt2 = db.readOnlyReplica { implicit s => userTopicRepo.getTopicMeanByUser(userId2, version) }
    val statOpt = getUserLDAStats(version)

    (vecOpt1, vecOpt2) match {
      case (Some(v1), Some(v2)) => Some(cosineDistance(scale(v1.mean, statOpt), scale(v2.mean, statOpt)))
      case _ => None
    }

  }

  def getTopicNames(uris: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Seq[Option[String]] = {
    val cutoff = (1.0f / numOfTopics) * 50
    val topicIdOpts = db.readOnlyReplica { implicit s =>
      uris.map { uri =>
        uriTopicRepo.getFirstTopicAndScore(uri, version) match {
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

  def explainFeed(userId: Id[User], uris: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Seq[Seq[Id[Keep]]] = {

    val MAX_KL_DIST = 0.5f // empirically this should be < 1.0
    val topK = 3

    def bestMatch(userFeats: Seq[(Id[Keep], Seq[LDATopic], LDATopicFeature)], uriFeat: URILDATopic): Seq[Id[Keep]] = {

      val bitSet = new BitSet(uriFeat.feature.get.value.size)
      List(uriFeat.firstTopic.get, uriFeat.secondTopic.get, uriFeat.thirdTopic.get).foreach { t => bitSet.set(t.index) }
      var numIntersects = 0

      val scored = userFeats.map {
        case (kid, topics, ufeat) =>
          val score = KL_divergence(ufeat.value, uriFeat.feature.get.value)
          numIntersects = 0

          topics.foreach { t =>
            if (bitSet.get(t.index)) numIntersects += 1
          }
          val multiplier = if (numIntersects >= 2) 1f else 0f

          (kid, score * multiplier)
      }
      val good = scored.filter { x => x._2 < MAX_KL_DIST && x._2 > 0 }.sortBy(_._2).take(10)
      Random.shuffle(good).take(topK).map { _._1 }
    }

    val userFeats = db.readOnlyReplica { implicit s => uriTopicRepo.getUserRecentURIFeatures(userId, version, min_num_words = 50, limit = 200) }
    val uriFeats = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURIs(uris, version) }
    uriFeats.map { uriFeatOpt =>
      uriFeatOpt match {
        case Some(uriFeat) if uriFeat.numOfWords > 50 => bestMatch(userFeats, uriFeat)
        case _ => Seq()
      }
    }
  }

  def uriKLDivergence(uriId1: Id[NormalizedURI], uriId2: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): Option[Float] = {
    val feat1 = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId1, version) }
    val feat2 = db.readOnlyReplica { implicit s => uriTopicRepo.getActiveByURI(uriId2, version) }
    (feat1, feat2) match {
      case (Some(f1), Some(f2)) if (f1.numOfWords > 50 && f2.numOfWords > 50) => Some(KL_divergence(f1.feature.get.value, f2.feature.get.value))
      case _ => None
    }
  }

  def recomputeUserLDAStats(implicit version: ModelVersion[DenseLDA]): Unit = {
    val users = db.readOnlyReplica { implicit s => userLDAStatRepo.getAllUsers(version) }
    log.info(s"recomputing user LDA Stats for ${users.size} users")
    var n = 0
    users.foreach { user =>
      userStatUpdatePlugin.updateUser(user)
      n += 1
      if (n % 100 == 0) log.info(s"recomputed ${n} user LDA Stats")
    }
  }

  // just for examine data
  def dumpFeature(dataType: String, id: Long)(implicit version: ModelVersion[DenseLDA]): JsValue = {
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

  def getSimilarURIs(uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): Seq[Id[NormalizedURI]] = {
    db.readOnlyReplica { implicit s =>
      uriTopicRepo.getActiveByURI(uriId, version) match {
        case Some(uriFeat) =>
          val (first, second, third) = (uriFeat.firstTopic.get, uriFeat.secondTopic.get, uriFeat.thirdTopic.get)
          uriTopicRepo.getURIsByTopics(first, second, third, version, limit = 50)
        case None => Seq()
      }
    }
  }

  def getSimilarLibraries(libId: Id[Library], limit: Int)(implicit version: ModelVersion[DenseLDA]): Seq[Id[Library]] = {

    def getCandidates(libId: Id[Library], feat: LibraryLDATopic): Seq[LibraryLDATopic] = {
      val first = feat.firstTopic.get
      val candidates = db.readOnlyReplica { implicit s => libTopicRepo.getLibraryByTopics(firstTopic = first, version = version, limit = 50) }
      candidates.filter(_.libraryId != libId)
    }

    def rankCandidates(feat: LibraryLDATopic, candidates: Seq[LibraryLDATopic]): Seq[Id[Library]] = {
      val topicBitSet = new BitSet(feat.topic.get.value.size)
      List(feat.firstTopic.get, feat.secondTopic.get, feat.thirdTopic.get).foreach { t => topicBitSet.set(t.index) }

      val idsAndScores = Seq.tabulate(candidates.size) { i =>
        val candidate = candidates(i)
        val id = candidate.libraryId
        val score = 1.0 - KL_divergence(feat.topic.get.value, candidate.topic.get.value) // score is higher the better
        val (same2, same3) = (candidate.secondTopic == feat.secondTopic, candidate.thirdTopic == feat.thirdTopic)
        val boost = (same2, same3) match {
          case (true, true) => 2f // full match
          case (true, false) | (false, true) => 1.5f // exact 1 match
          case (false, false) =>
            val interCnt = List(candidate.secondTopic.get.index, candidate.thirdTopic.get.index).count(topicBitSet.get(_))
            interCnt match {
              case 2 => 1.5f // permuted 2 matches
              case 1 => 1f // permuted 1 match
              case 0 => 0f // no match
            }
        }
        (id, score * boost)
      }
      idsAndScores.filter(_._2 > 0).sortBy(-_._2).map { _._1 }
    }

    val libFeatOpt = db.readOnlyReplica { implicit s => libTopicRepo.getActiveByLibraryId(libId, version) }
    libFeatOpt match {
      case None => Seq()
      case Some(feat) =>
        val candidates = getCandidates(libId, feat)
        val ranked = rankCandidates(feat, candidates)
        ranked.take(limit)
    }
  }

}
