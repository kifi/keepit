package com.keepit.cortex.plugins

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.InMemoryCommitInfoStore
import com.keepit.cortex.store.VersionedInMemoryStore

trait FeaturePluginTestHelper {
  case class Foo(id: Id[Foo])
  class FakeModel extends StatModel

  case class FooRepresentation(arr: Array[Float]) extends FeatureRepresentation[Foo, FakeModel]{
    def vectorize = arr
  }

  class FooFeatStore extends VersionedInMemoryStore[Id[Foo], FakeModel, FeatureRepresentation[Foo, FakeModel]]

  class FooFeatureRepresenter extends FeatureRepresenter[Foo, FakeModel]{
    val version = ModelVersion[FakeModel](1)
    val dimension: Int = 2
    def apply(foo: Foo): Option[FooRepresentation] = {
      val value = foo.id.id.toFloat
      Some(FooRepresentation(Array(value, value)))
    }
  }

  class FooDataPuller extends DataPuller[Foo]{
    val allFoo = (1 to 550).map{ i => Foo(Id[Foo](i))}
    def getSince(lowSeq: SequenceNumber[Foo], limit : Int) = allFoo.filter(_.id.id > lowSeq.value).take(limit)
    def getBetween(lowSeq: SequenceNumber[Foo], highSeq: SequenceNumber[Foo]) = {
      allFoo.filter(x => x.id.id > lowSeq.value && x.id.id <= highSeq.value)
    }
  }

  def setup() = {
    val fooFeatStore = new FooFeatStore
    val fooRepresenter = new FooFeatureRepresenter
    val commitStore = new InMemoryCommitInfoStore[Foo, FakeModel]
    val fakePuller = new FooDataPuller
    (fooRepresenter, fooFeatStore, commitStore, fakePuller)
  }
}
