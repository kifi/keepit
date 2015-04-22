package com.keepit.curator.feedback

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.commanders.UriRecoScore
import com.keepit.curator.model.{ UserRecoFeedbackCounter, UriRecoFeedbackValue, UriRecommendation, UserRecoFeedbackCounterRepo }
import com.keepit.model.User

@Singleton
class UserRecoFeedbackTrackingCommander @Inject() (
    db: Database,
    feedbackRepo: UserRecoFeedbackCounterRepo) {

  def trackFeedback(item: UriRecommendation, feedback: UriRecoFeedbackValue): Unit = {
    val bucketIdOpt = FeedbackBucketMapper.getBucketId(item)
    if (bucketIdOpt.isDefined) {
      val bucketId = bucketIdOpt.get
      val curr = db.readWrite { implicit s => feedbackRepo.getByUser(item.userId) }
      val toSave = curr match {
        case None => // init arrays, save count, save
          val counter = UserRecoFeedbackCounter.empty(item.userId, FeedbackBucketMapper.fullBucketSize)
          counter.updateWithFeedback(bucketId, feedback)
        case Some(counter) => counter.updateWithFeedback(bucketId, feedback)

      }
      db.readWrite { implicit s => feedbackRepo.save(toSave) }
    }
  }
}

object FeedbackBucketMapper {
  private val socialBucketSize = 2
  private val socialBucketThresh = 0.5f
  private val topicBucketSize = 256
  val fullBucketSize = socialBucketSize * topicBucketSize

  private def toSocialBucket(score: Float): Int = {
    if (score <= 0f) 0
    else (score / socialBucketThresh).toInt min (socialBucketSize - 1)
  }

  private def toBucketId(socialBucket: Int, topicBucket: Int): Int = {
    socialBucket * topicBucketSize + topicBucket
  }

  def getBucketId(socialScore: Float, topicId: Int): Option[Int] = {
    if (topicId < 0 || topicId >= topicBucketSize) None
    else {
      val socialBucket = toSocialBucket(socialScore)
      Some(toBucketId(socialBucket, topicId))
    }
  }

  def getBucketId(item: UriRecommendation): Option[Int] = item.topic1.flatMap { tid => getBucketId(item.allScores.socialScore, tid.index) }
}

@Singleton
class UserRecoFeedbackInferenceCommander @Inject() (
    db: Database,
    feedbackRepo: UserRecoFeedbackCounterRepo) extends Logging {

  private def getInferencer(userId: Id[User]): Option[UserRecoFeedbackInferencer] = {
    db.readOnlyReplica { implicit s => feedbackRepo.getByUser(userId) }.map { counter => new UserRecoFeedbackInferencer(counter) }
  }

  def applyMultipliers(userId: Id[User], recos: Seq[UriRecoScore]): Seq[UriRecoScore] = {
    val inferOpt = getInferencer(userId)
    inferOpt match {
      case Some(infer) =>
        log.info(s"applying feedback to user ${userId}. ")
        recos.map { reco => infer.applyMultiplier(reco) }
      case None => recos
    }
  }
}
