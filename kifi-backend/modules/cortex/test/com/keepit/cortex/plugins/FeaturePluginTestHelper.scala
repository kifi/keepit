package com.keepit.cortex.plugins

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.InMemoryCommitInfoStore
import com.keepit.cortex.store.VersionedInMemoryStore
import com.keepit.common.db.ModelWithSeqNumber
import org.joda.time.DateTime

trait FeaturePluginTestHelper {
  case class Foo(id: Option[Id[Foo]], seq: SequenceNumber[Foo]) extends ModelWithSeqNumber[Foo] {
    def withId(id: Id[Foo]): Foo = ???
    def withUpdateTime(time: DateTime): Foo = ???
  }
  class FakeModel extends StatModel

  case class FooRepresentation(arr: Array[Float]) extends FeatureRepresentation[Foo, FakeModel] {
    def vectorize = arr
  }

  class FooFeatStore extends VersionedInMemoryStore[Id[Foo], FakeModel, FeatureRepresentation[Foo, FakeModel]]

  class FooModuloIdInMemoryStore extends VersionedInMemoryStore[Id[Foo], FakeModel, FeatureRepresentation[Foo, FakeModel]] {
    override def syncGet(key: Id[Foo], version: ModelVersion[FakeModel]): Option[FeatureRepresentation[Foo, FakeModel]] = {
      if (key.id.toInt % 3 == 1) super.syncGet(key, version)
      else None
    }
  }

  class FooFeatureRepresenter extends FeatureRepresenter[Foo, FakeModel, FooRepresentation] {
    val version = ModelVersion[FakeModel](1)
    val dimension: Int = 2
    def apply(foo: Foo): Option[FooRepresentation] = {
      val value = foo.id.get.id.toFloat
      Some(FooRepresentation(Array(value, value)))
    }
  }

  class FooDataPuller extends DataPuller[Foo] {
    val allFoo = (1 to 550).map { i => Foo(Some(Id[Foo](i)), SequenceNumber[Foo](i)) }
    def getSince(lowSeq: SequenceNumber[Foo], limit: Int) = allFoo.filter(_.id.get.id > lowSeq.value).take(limit)
  }

  def setup() = {
    val fooFeatStore = new FooFeatStore
    val fooRepresenter = new FooFeatureRepresenter
    val commitStore = new InMemoryCommitInfoStore[Foo, FakeModel]
    val fakePuller = new FooDataPuller
    (fooRepresenter, fooFeatStore, commitStore, fakePuller)
  }

  def setup2() = {
    val fooFeatStore = new FooModuloIdInMemoryStore
    val fooRepresenter = new FooFeatureRepresenter
    val commitStore = new InMemoryCommitInfoStore[Foo, FakeModel]
    val fakePuller = new FooDataPuller
    (fooRepresenter, fooFeatStore, commitStore, fakePuller)
  }
}
