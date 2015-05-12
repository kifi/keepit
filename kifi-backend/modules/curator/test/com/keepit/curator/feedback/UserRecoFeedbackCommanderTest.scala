package com.keepit.curator.feedback

import com.keepit.common.db.Id
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.curator.CuratorTestInjector
import com.keepit.curator.model._
import com.keepit.model.{ User, NormalizedURI }
import org.specs2.mutable.Specification

class UserRecoFeedbackCommanderTest extends Specification with CuratorTestInjector {
  "FeedbackBucketMapper" should {
    "map item to the right bucket" in {
      (0 until 256).map { i => FeedbackBucketMapper.getBucketId(-0.1f, i).get }.toList === (0 until 256)
      (0 until 256).map { i => FeedbackBucketMapper.getBucketId(0.1f, i).get }.toList === (0 until 256)
      (0 until 256).map { i => FeedbackBucketMapper.getBucketId(0.7f, i).get }.toList === (256 until 512)
      (0 until 256).map { i => FeedbackBucketMapper.getBucketId(1.7f, i).get }.toList === (256 until 512)
    }
  }

  "UserRecoFeedbackTrackingCommander" should {
    "work" in {
      withDb() { implicit injector =>
        val repo = inject[UserRecoFeedbackCounterRepo]
        val commander = new UserRecoFeedbackTrackingCommander(db, repo)
        val item = UriRecommendation(uriId = Id[NormalizedURI](1), userId = Id[User](1), masterScore = 0.15f,
          allScores = UriScores(socialScore = 1.0f,
            popularityScore = 1.0f,
            overallInterestScore = 1.0f,
            recentInterestScore = 1.0f,
            recencyScore = 1.0f,
            priorScore = 1.0f,
            rekeepScore = 1.0f,
            discoveryScore = 1.0f,
            curationScore = None,
            multiplier = Some(0.01f),
            libraryInducedScore = Some(0f)),
          viewed = 0, clicked = 0, kept = false, attribution = SeedAttribution.EMPTY,
          topic1 = Some(LDATopic(0)), topic2 = None)

        val bucketId = FeedbackBucketMapper.getBucketId(item).get
        bucketId === 256

        commander.trackFeedback(item, UriRecoFeedbackValue.CLICKED)
        var counter = db.readOnlyReplica { implicit s => repo.getByUser(Id[User](1)) }.get
        counter.posSignals.get(bucketId) === 1
        counter.posSignals.toArray().sum === 1
        counter.negSignals.toArray().sum === 0

        commander.trackFeedback(item, UriRecoFeedbackValue.DISLIKE)
        counter = db.readOnlyReplica { implicit s => repo.getByUser(Id[User](1)) }.get
        counter.negSignals.get(bucketId) === 5
        counter.posSignals.toArray().sum === 1
        counter.negSignals.toArray().sum === 5

      }
    }
  }
}
