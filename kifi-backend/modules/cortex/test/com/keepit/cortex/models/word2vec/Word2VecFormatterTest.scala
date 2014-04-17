package com.keepit.cortex.models.word2vec

import org.specs2.mutable.Specification

class Word2VecFormatterTest extends Specification{
  "word2vec formmater" should {
    "work" in {
      val mapper = Map("hello" -> Array(1f, -0.7f), "dow" -> Array(9.9f, -0.123f))
      val dim = 2

      val w2v = Word2VecImpl(dim, mapper)

      val bytes = Word2VecFormatter.toBinary(w2v)
      val back = Word2VecFormatter.fromBinary(bytes)

      back.dimension === w2v.dimension
      w2v.mapper.keySet.foreach{ k =>
        w2v.mapper(k) === back.mapper(k)
      }

      1 === 1
    }
  }
}
