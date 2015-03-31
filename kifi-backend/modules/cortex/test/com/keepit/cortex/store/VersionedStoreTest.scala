package com.keepit.cortex.store

import org.specs2.mutable.Specification
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.ModelVersion
import play.api.libs.json._
import com.keepit.cortex.core.Versionable

class VersionedStoreTest extends Specification {
  "ModelVersion" should {
    "serialize" in {
      class FakeModel extends StatModel
      val v = ModelVersion[FakeModel](2)
      implicit val format = ModelVersion.format[FakeModel]
      val js = Json.toJson(v)
      Json.fromJson[ModelVersion[FakeModel]](js).get === v
    }
  }

  "VersionedStore" should {
    "work" in {
      class FakeModel extends StatModel
      val v1 = ModelVersion[FakeModel](1)
      val v2 = ModelVersion[FakeModel](2)

      case class Word(value: String)
      case class FakeFeature(value: String) extends Versionable[FakeModel]
      class FakeWordFeatureStore extends VersionedInMemoryStore[Word, FakeModel, FakeFeature]

      val store = new FakeWordFeatureStore()
      store.+=(Word("hello"), v1, FakeFeature("hello-feature"))

      store.syncGet(Word("hello"), v1) === Some(FakeFeature("hello-feature"))
      store.syncGet(Word("hello"), v2) === None

      store.+=(Word("world"), v1, FakeFeature("world-feature-v1"))
      store.+=(Word("world"), v2, FakeFeature("world-feature-v2"))
      store.syncGet(Word("world"), v1) === Some(FakeFeature("world-feature-v1"))
      store.syncGet(Word("world"), v2) === Some(FakeFeature("world-feature-v2"))

      store.-=(Word("world"), v1)
      store.-=(Word("world"), v2)
      store.syncGet(Word("world"), v1) === None
      store.syncGet(Word("world"), v2) === None
    }
  }
}
