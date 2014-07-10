package com.keepit.cortex.store

import org.specs2.mutable.Specification
import com.keepit.cortex.core._

class StatModelStoreTest extends Specification {
  "statModel and InMemoryBlobStore" should {
    "work" in {

      case class FakeModel(modelData: Array[Float]) extends StatModel

      object FakeModelFormatter extends BinaryFormatter[FakeModel] {
        def toBinary(m: FakeModel): Array[Byte] = StoreUtil.FloatArrayFormmater.toBinary(m.modelData)
        def fromBinary(bytes: Array[Byte]): FakeModel = FakeModel(StoreUtil.FloatArrayFormmater.fromBinary(bytes))
      }

      class FakeModelStore extends InMemoryStatModelStore[FakeModel] {
        val formatter = FakeModelFormatter
      }

      val modelStore = new FakeModelStore

      val v1 = ModelVersion[FakeModel](1)
      val v2 = ModelVersion[FakeModel](2)
      val m1 = FakeModel(Array(1f, 2f, 3f))
      val m2 = FakeModel(Array(4f, 5f, 6f, 7f))

      modelStore.+=(v1 -> m1)
      modelStore.+=(v2 -> m2)

      modelStore.-=(v1) must throwA[UnsupportedOperationException]
    }
  }
}
