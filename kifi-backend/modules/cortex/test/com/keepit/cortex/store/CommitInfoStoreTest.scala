package com.keepit.cortex.store

import org.specs2.mutable.Specification
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.ModelVersion
import play.api.libs.json._
import com.keepit.cortex.core.Versionable
import com.keepit.cortex.plugins._
import com.keepit.common.time._
import org.joda.time.DateTime

class CommitInfoStoreTest extends Specification {
  "CommitInfo" should {
    "serialize" in {
      class FakeModel extends StatModel
      class FOO
      val seq = FeatureStoreSequenceNumber[FOO, FakeModel](2L)
      val version = ModelVersion[FakeModel](7)
      val committedAt = currentDateTime
      val info = CommitInfo(seq, version, committedAt)
      implicit val format = CommitInfo.format[FOO, FakeModel]
      val js = Json.toJson(info)
      val info2 = Json.fromJson[CommitInfo[FOO, FakeModel]](js).get
      info === info2
    }
  }

  "InMemoryCommitStore" should {
    "work" in {
      class FakeModel extends StatModel
      class FOO

      val store = new InMemoryCommitInfoStore[FOO, FakeModel]

      val seq = FeatureStoreSequenceNumber[FOO, FakeModel](2L)
      val version = ModelVersion[FakeModel](7)
      val committedAt = currentDateTime
      val info = CommitInfo(seq, version, committedAt)
      val key = CommitInfoKey[FOO, FakeModel](version)

      store.+=(key, info)
      store.syncGet(key).get === info

      val key2 = CommitInfoKey[FOO, FakeModel](ModelVersion[FakeModel](42))
      store.syncGet(key2) === None

    }
  }
}
