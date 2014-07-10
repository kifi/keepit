package com.keepit.cortex.store

import org.specs2.mutable.Specification
import com.keepit.cortex.core._
import com.keepit.common.db.Id

class FloatVecFeatureStoreTest extends Specification {
  "FloatVecFeatureStore" should {
    "correctly pack and uppack feature data" in {

      class FakeModel extends StatModel
      class Foo(id: Id[Foo])

      val version = ModelVersion[FakeModel](1)

      class TestStore extends S3BlobFloatVecFeatureStore[Id[Foo], Foo, FakeModel] {
        val accessLog = null
        val amazonS3Client = null
        val bucketName = null
        val prefix = ""
        def testEncodeValue(feature: FeatureRepresentation[Foo, FakeModel]): Array[Byte] = super.encodeValue(feature)
        def testDecodeValue(data: Array[Byte]): FeatureRepresentation[Foo, FakeModel] = super.decodeValue(data)
      }

      val store = new TestStore

      val feat = FloatVecFeature[Foo, FakeModel](Array(0.2f, 0.7f))
      val blob = store.testEncodeValue(feat)
      store.testDecodeValue(blob).vectorize === feat.vectorize
    }
  }
}
