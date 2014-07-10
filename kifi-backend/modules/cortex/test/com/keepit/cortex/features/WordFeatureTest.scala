package com.keepit.cortex.features

import org.specs2.mutable.Specification
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.ModelVersion

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
      fakeDocRep.apply(doc).get.vectorize === Array(1.5f / 2, 0.5f / 2)

      doc = Document("@%#%#^ *&^&**".split(" "))
      fakeDocRep.apply(doc) === None
    }
  }

}
