package com.keepit.curator.feedback

import com.keepit.curator.model.UserRecoFeedbackCounter

class UserRecoFeedbackInferencer(fb: UserRecoFeedbackCounter) {

  // range: [0, 2].
  def getMultiplier(bucket: Int): Float = {
    val (ups, downs) = (fb.upVotes.get(bucket), fb.downVotes.get(bucket))
    val delta = Math.tanh((ups - downs) / 3f).toFloat
    1 + delta
  }
}
