package com.keepit.cortex.models.word2vec

import org.specs2.mutable.Specification

class Word2VecFormatterTest extends Specification {
  "word2vec formmater" should {
    "work" in {
      val mapper = Map("hello" -> Array(1f, -0.7f), "dow" -> Array(9.9f, -0.123f))
      val dim = 2

      val w2v = Word2VecImpl(dim, mapper)

      val bytes = Word2VecFormatter.toBinary(w2v)
      val back = Word2VecFormatter.fromBinary(bytes)

      back.dimension === w2v.dimension
      w2v.mapper.keySet.foreach { k =>
        w2v.mapper(k) === back.mapper(k)
      }

      1 === 1
    }
  }

  "RichWord2VecURIFeatureFormat" should {
    "work" in {

      var dim = 4
      var vec = Array(1.1f, -2.3f, 4f, -3.14f)
      var keywords = Array("apple", "orange")
      var bow = Map("apple" -> 7, "orange" -> 5, "banana" -> 2)
      var feat = RichWord2VecURIFeature(dim, vec, keywords, bow)

      var bytes = RichWord2VecURIFeatureFormat.toBinary(feat)
      var back = RichWord2VecURIFeatureFormat.fromBinary(bytes)
      back.dim === feat.dim
      back.vec === feat.vec
      back.keywords === feat.keywords
      back.bagOfWords.toArray === feat.bagOfWords.toArray

      keywords = Array.empty[String]
      bow = Map.empty[String, Int]
      feat = RichWord2VecURIFeature(dim, vec, keywords, bow)

      bytes = RichWord2VecURIFeatureFormat.toBinary(feat)
      back = RichWord2VecURIFeatureFormat.fromBinary(bytes)
      back.dim === feat.dim
      back.vec === feat.vec
      back.keywords === feat.keywords
      back.bagOfWords.toArray === feat.bagOfWords.toArray

      keywords = Array.empty[String]
      bow = Map("apple" -> 7, "orange" -> 5, "banana" -> 2)

      bytes = RichWord2VecURIFeatureFormat.toBinary(feat)
      back = RichWord2VecURIFeatureFormat.fromBinary(bytes)
      back.dim === feat.dim
      back.vec === feat.vec
      back.keywords === feat.keywords
      back.bagOfWords.toArray === feat.bagOfWords.toArray

      keywords = Array("apple", "orange")
      bow = Map.empty[String, Int]
      bytes = RichWord2VecURIFeatureFormat.toBinary(feat)
      back = RichWord2VecURIFeatureFormat.fromBinary(bytes)
      back.dim === feat.dim
      back.vec === feat.vec
      back.keywords === feat.keywords
      back.bagOfWords.toArray === feat.bagOfWords.toArray

    }
  }
}
