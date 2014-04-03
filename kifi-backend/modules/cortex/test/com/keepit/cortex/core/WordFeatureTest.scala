package com.keepit.cortex.core

import org.specs2.mutable.Specification

class WordFeatureTest extends Specification with WordFeatureTestHelper {

  "basic word feature and doc feature " should {
    "work" in {
      var doc = Document("red orange yellow banana".split(" "))
      fakeDocRep.apply(doc).get.vectorize === Array(0f, 1f)

      doc = Document("intel and amd are bros".split(" "))
      fakeDocRep.apply(doc).get.vectorize === Array(1f, 0f)

      doc = Document("intel intel intel !!!".split(" "))
      fakeDocRep.apply(doc).get.vectorize === Array(1f, 0f)

      doc = Document("apple intel".split(" "))
      fakeDocRep.apply(doc).get.vectorize === Array(1.5f/2, 0.5f/2)

      doc = Document("@%#%#^ *&^&**".split(" "))
      fakeDocRep.apply(doc) === None
    }
  }

}

trait WordFeatureTestHelper {

  val company = Array(1f, 0f)
  val fruit = Array(0f, 1f)
  val halfHalf = Array(0.5f, 0.5f)
  val mapper = Map("apple" -> halfHalf, "orange" -> fruit, "banana" -> fruit, "intel" -> company, "amd" -> company)

  class FakeWordModel extends StatModel
  val fakeWordRep = new HashMapWordRepresenter[FakeWordModel](mapper){
    val version = ModelVersion[FakeWordModel](1)
    val dimension = 2
  }

  val fakeDocRep = new NaiveSumDocRepresenter[FakeWordModel](fakeWordRep){
    override val minValidTerms = 1

    def normalize(vec: Array[Float]): Array[Float] = {
      val s = vec.sum
      vec.map{ x => x/s}
    }
  }

}
