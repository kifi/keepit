package com.keepit.cortex.store

import org.specs2.mutable.Specification

class StoreUtilTest extends Specification {
  "float array formatter" should {
    "work" in {
      val arr = Array(0f, 0.4f, -0.5f)
      val bytes = StoreUtil.FloatArrayFormmater.toBinary(arr)
      StoreUtil.FloatArrayFormmater.fromBinary(bytes) === arr
    }
  }

  "DenseWordVecFormatter" should {
    "work" in {

      val (w1, w2, w3) = ("apple", "orange", "banana")
      val (v1, v2, v3) = (Array(-2f, .7f, .9f), Array(.8f, 128f, -3f), Array(-7f, 100f, .9f))
      val mapper = Map(w1 -> v1, w2 -> v2, w3 -> v3)
      val dim = 3

      val bytes = StoreUtil.DenseWordVecFormatter.toBinary(dim, mapper)
      val (dim2, mapper2) = StoreUtil.DenseWordVecFormatter.fromBinary(bytes)

      dim2 === dim

      for (k <- mapper.keySet) {
        mapper(k) === mapper2(k)
      }

      1 === 1
    }
  }

}
