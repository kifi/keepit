package com.keepit.curator.model

import com.kifi.macros.json

@json case class UserFeedbackCountView(socialBucket: Int, topicBucket: Int, posCount: Int, negCount: Int)
