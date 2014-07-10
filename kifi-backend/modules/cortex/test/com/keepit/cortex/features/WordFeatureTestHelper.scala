package com.keepit.cortex.features

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.ModelVersion

trait WordFeatureTestHelper {

  val company = Array(1f, 0f)
  val fruit = Array(0f, 1f)
  val halfHalf = Array(0.5f, 0.5f)
  val mapper = Map("apple" -> halfHalf, "orange" -> fruit, "banana" -> fruit, "intel" -> company, "amd" -> company)

  class FakeWordModel extends StatModel
  val fakeWordRep = new HashMapWordRepresenter[FakeWordModel](2, mapper) {
    val version = ModelVersion[FakeWordModel](1)
  }

  val fakeDocRep = new NaiveSumDocRepresenter[FakeWordModel](fakeWordRep) {
    override val minValidTerms = 1

    def normalize(vec: Array[Float]): Array[Float] = {
      val s = vec.sum
      vec.map { x => x / s }
    }
  }

}
