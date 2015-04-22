package com.keepit.curator.feedback

import com.keepit.curator.commanders.UriRecoScore
import com.keepit.curator.model.{ UserFeedbackCountView, UserRecoFeedbackCounter }

class UserRecoFeedbackInferencer(fb: UserRecoFeedbackCounter) {

  private val mulipliers: Map[Int, Float] = {
    val ws = (fb.upVotes.toArray() zip fb.downVotes.toArray()) map { case (up, down) => 1f + Math.tanh((up - down) / 3f).toFloat } // range in [0, 2]
    ws.zipWithIndex.map { _.swap }.toMap
  }

  def applyMultiplier(reco: UriRecoScore): UriRecoScore = {
    require(reco.reco.userId == fb.userId, "illegal use of feedback multiplier: reco user id and feedback counter user id not match")
    FeedbackBucketMapper.getBucketId(reco.reco) match {
      case Some(bucket) => reco.copy(score = reco.score * mulipliers(bucket))
      case None => reco
    }
  }

  def getNontrivialVotes: Seq[UserFeedbackCountView] = {
    (0 until fb.upVotes.getSize()).flatMap { i =>
      val (up, down) = (fb.upVotes.get(i), fb.downVotes.get(i))
      val (socialBkt, topicBkt) = FeedbackBucketMapper.toComponentIds(i)
      if (up + down > 0) Some(UserFeedbackCountView(socialBkt, topicBkt, up, down)) else None
    }
  }

  def getNontrivialSignals: Seq[UserFeedbackCountView] = {
    (0 until fb.posSignals.getSize()).flatMap { i =>
      val (pos, neg) = (fb.posSignals.get(i), fb.negSignals.get(i))
      val (socialBkt, topicBkt) = FeedbackBucketMapper.toComponentIds(i)
      if (pos + neg > 0) Some(UserFeedbackCountView(socialBkt, topicBkt, pos, neg)) else None
    }
  }

}

