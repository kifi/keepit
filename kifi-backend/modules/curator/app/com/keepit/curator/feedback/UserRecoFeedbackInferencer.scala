package com.keepit.curator.feedback

import com.keepit.curator.commanders.UriRecoScore
import com.keepit.curator.model.{ UserFeedbackCountView, UserRecoFeedbackCounter }
import org.apache.commons.math3.distribution.BetaDistribution

class UserRecoFeedbackInferencer(fb: UserRecoFeedbackCounter) {
  import BayesianMultiplier._

  private val voteMulipliers: Map[Int, Float] = {
    val ws = (fb.upVotes.toArray() zip fb.downVotes.toArray()) map { case (up, down) => 1f + Math.tanh((up - down) / 3f).toFloat } // range in [0, 2]
    ws.zipWithIndex.map { _.swap }.toMap
  }

  private val signalMultipliers: Map[Int, Float] = {
    val ws = (fb.posSignals.toArray() zip fb.negSignals.toArray()) map { case (pos, neg) => betaMultiplier(pos, neg) }
    ws.zipWithIndex.map { _.swap }.toMap
  }

  private def comboMultiplier(bucketId: Int) = {
    val (v, s) = ((voteMulipliers(bucketId)), signalMultipliers(bucketId))
    if (v == 1f) s // likely no votes on this bucket. use singal only
    else 0.5f * v + 0.5f * s
  }

  def applyMultiplier(reco: UriRecoScore): UriRecoScore = {
    require(reco.reco.userId == fb.userId, "illegal use of feedback multiplier: reco user id and feedback counter user id not match")
    FeedbackBucketMapper.getBucketId(reco.reco) match {
      case Some(bucket) => reco.copy(score = reco.score * comboMultiplier(bucket))
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

object BayesianMultiplier {
  val numGridPts = 21
  val stepSize = 1.0f / (numGridPts - 1)
  val gridPoints = (0 until numGridPts).map { i => i * stepSize }
  val defaultMultiplier = (x: Float) => 2 * x - 1

  def betaMultiplier(heads: Int, tails: Int, pseudoHeads: Int = 1, pseudoTails: Int = 1, multiplier: Function1[Float, Float] = defaultMultiplier): Float = {
    val mean = (heads + pseudoHeads) * 1f / (heads + pseudoHeads + tails + pseudoTails)
    val shift = if (heads == tails) {
      0f // speed optimization
    } else if (mean < 0.1 || mean > 0.9) {
      multiplier(mean) // close to a delta function
    } else {
      val betaDist = new BetaDistribution(heads + pseudoHeads, tails + pseudoTails)
      val integral = gridPoints.map { x => betaDist.density(x) * multiplier(x) }.sum.toFloat * stepSize
      integral.toFloat
    }
    1 + shift // shift is in [-1, 1]
  }
}

