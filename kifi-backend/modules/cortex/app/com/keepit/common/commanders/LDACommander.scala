package com.keepit.common.commanders

import java.util.BitSet

import com.google.inject.{ ImplementedBy, Inject, Singleton }
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
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

@ImplementedBy(classOf[LDACommanderImpl])
trait LDACommander {
  // admin tools
  def numOfTopics(implicit version: ModelVersion[DenseLDA]): Int
  def userTopicMean(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Option[UserLDAInterests]
  def libraryTopic(libId: Id[Library])(implicit version: ModelVersion[DenseLDA]): Option[LibraryLDATopic]
  def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: ModelVersion[DenseLDA]): Option[Float]
  def libraryInducedUserURIInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): Option[LDAUserURIInterestScore]
  def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: ModelVersion[DenseLDA]): (Seq[Id[User]], Seq[Float])
  def userSimilairty(userId1: Id[User], userId2: Id[User])(implicit version: ModelVersion[DenseLDA]): Option[Float]
  def getSimilarURIs(uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): Seq[Id[NormalizedURI]]
  def dumpFeature(dataType: String, id: Long)(implicit version: ModelVersion[DenseLDA]): JsValue
  def recomputeUserLDAStats(implicit version: ModelVersion[DenseLDA]): Unit
  def sampleURIs(topicId: Int)(implicit version: ModelVersion[DenseLDA]): Seq[(Id[NormalizedURI], Float)]
  def uriKLDivergence(uriId1: Id[NormalizedURI], uriId2: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): Option[Float]

  // exteral service: e.g., scoring methods
  def userLibrariesScores(userId: Id[User], libIds: Seq[Id[Library]])(implicit version: ModelVersion[DenseLDA]): Seq[Option[Float]]
  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): LDAUserURIInterestScores
  def gaussianUserUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: ModelVersion[DenseLDA]): LDAUserURIInterestScores
  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Future[Seq[LDAUserURIInterestScores]]
  def getTopicNames(uris: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Seq[Option[String]]
  def explainFeed(userId: Id[User], uris: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Seq[Seq[Id[Keep]]]
  def getSimilarLibraries(libId: Id[Library], limit: Int)(implicit version: ModelVersion[DenseLDA]): Seq[Id[Library]]
}

@Singleton
class LDACommanderImpl @Inject() (
    infoCommander: LDAInfoCommander,
    personaCommander: LDAPersonaCommander,
    db: Database,
    userTopicRepo: UserLDAInterestsRepo,
    uriTopicRepo: URILDATopicRepo,
    userLDAStatsRetriever: UserLDAStatisticsRetriever,
    topicInfoRepo: LDAInfoRepo,
    userLDAStatRepo: UserLDAStatsRepo,
    libTopicRepo: LibraryLDATopicRepo,
    userStatUpdatePlugin: LDAUserStatDbUpdatePlugin,
    ldaRelatedLibRepo: LDARelatedLibraryRepo) extends LDACommander with Logging {

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

  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: ModelVersion[DenseLDA]): Future[Seq[LDAUserURIInterestScores]] = {
    def isInJunkTopic(uriTopicOpt: Option[URILDATopic], junks: Set[Int]): Boolean = uriTopicOpt.exists(x => x.firstTopic.exists(t => junks.contains(t.index)))

    type UserFeaturesCombo = (Option[UserLDAInterests], Option[UserLDAStats], Seq[LibraryTopicMean])

    def getUserFeaturesCombo(userId: Id[User], version: ModelVersion[DenseLDA]): UserFeaturesCombo = {
      val userInterestOpt = userTopicRepo.getByUser(userId, version)
      val userInterestStatOpt = userLDAStatRepo.getActiveByUser(userId, version)
      val libFeats = db.readOnlyReplica { implicit s => libTopicRepo.getUserFollowedLibraryFeatures(userId, version) }
      (userInterestOpt, userInterestStatOpt, libFeats)
    }

    def scoreURI(uriTopicOpt: Option[URILDATopic], userFeatCombo: UserFeaturesCombo, userPersonas: Seq[PersonaLDAFeature]): LDAUserURIInterestScores = {
      val (userInterestOpt, userInterestStatOpt, libFeats) = userFeatCombo
      val s1 = computeCosineInterestScore(uriTopicOpt, userInterestOpt)
      val s2 = computeGaussianInterestScore(uriTopicOpt, userInterestStatOpt)
      val s3 = libraryInducedUserURIInterestScore(libFeats, uriTopicOpt)
      val s4 = computePersonaInducedInterestScore(userPersonas, uriTopicOpt)
      val s5 = combineScores(s2.global, s4)
      val (topic1, topic2) = (uriTopicOpt.flatMap(_.firstTopic), uriTopicOpt.flatMap(_.secondTopic))
      LDAUserURIInterestScores(s2.global, s1.recency, s3, topic1, topic2)
    }

    // tweak later
    def combineScores(keepInduced: Option[LDAUserURIInterestScore], personaInduced: Option[LDAUserURIInterestScore]): Option[LDAUserURIInterestScore] = {
      keepInduced
    }

    val junkTopics = infoCommander.inactiveTopics(version)
    personaCommander.getUserPersonaFeatures(userId).map { personaFeats =>

      db.readOnlyReplica { implicit s =>
        val userFeatsCombo = getUserFeaturesCombo(userId, version)
        val uriTopicOpts = uriTopicRepo.getActiveByURIs(uriIds, version)
        assert(uriTopicOpts.size == uriIds.size, "retreived uri lda features size doesn't match with num of uris")

        uriTopicOpts.map { uriTopicOpt =>
          if (!isInJunkTopic(uriTopicOpt, junkTopics)) scoreURI(uriTopicOpt, userFeatsCombo, personaFeats)
          else LDAUserURIInterestScores(None, None, None)
        }
      }
    }

  }

  private def computePersonaInducedInterestScore(personas: Seq[PersonaLDAFeature], uriTopicOpt: Option[URILDATopic]): Option[LDAUserURIInterestScore] = {
    if (personas.isEmpty) None
    else {
      uriTopicOpt match {
        case Some(feat) =>
          feat.feature.map { uriFeat =>
            val scores = personas.map { p => cosineDistance(uriFeat.value, p.feature.mean) }
            val score = scores.max
            val conf = computeURIConfidence(feat.numOfWords, feat.timesFirstTopicChanged)
            LDAUserURIInterestScore(score, conf)
          }
        case None => None
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
      val divs = libsFeats.map { v => KL_divergence(v, uriFeat) }
      val scores = divs.map { div => (1f - div) max 0f }.sortBy(x => -x)
      val score = if (scores.size <= 2) {
        scores(0)
      } else {
        // ensemble of the scores
        require(scores.size >= 2, "scores size should be >= 2")
        val r = (scores.size - 1).toFloat / scores.size
        val weight = bumpFunction(r) // weight of the max score, decays as r increases.
        val max = scores(0)
        val remains = scores.drop(1).take(5)
        val avg = remains.sum / remains.size
        weight * max + (1 - weight) * avg
      }
      val confidence = computeURIConfidence(numWords, numTopicChanges)
      Some(LDAUserURIInterestScore(score, confidence))
    } else None
  }

  // max value normalized to 1
  private def bumpFunction(x: Float): Float = {
    if (math.abs(x) >= 1f) 0f
    else {
      val exponent = 1.0 / (1 - x * x)
      exp(1 - exponent)
    }
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

  private def getUserLDAStats(version: ModelVersion[DenseLDA]): Option[UserLDAStatistics] = {
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
    db.readOnlyReplica { implicit s =>
      ldaRelatedLibRepo.getTopNeighborIdsAndWeights(libId, version, limit).map { _._1 }
    }
  }
}
