package com.keepit.cortex.models.word2vec

import com.kifi.macros.json

@json
case class Word2VecKeywords(cosine: Seq[String], freq: Seq[String], wordCounts: Int)