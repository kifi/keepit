package com.keepit.cortex.plugins

import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber


class FeatureRetrievalTest extends Specification with FeaturePluginTestHelper{
  "feature retrieval" should {
    "work" in {

      val (fooRepresenter, fooFeatStore, commitStore, fakePuller) = setup()

      val updater = new FeatureUpdater[Id[Foo], Foo, FakeModel](
          fooRepresenter, fooFeatStore, commitStore, fakePuller){
        def getSeqNumber(foo: Foo) = SequenceNumber[Foo](foo.id.id)
        def genFeatureKey(foo: Foo) = foo.id
      }

      updater.update()

      val retriever = new FeatureRetrieval[Id[Foo], Foo, FakeModel](
        fooFeatStore, commitStore, fakePuller
      ){
        def genFeatureKey(foo: Foo) = foo.id
      }

      val version = fooRepresenter.version
      var reps = retriever.getSince(SequenceNumber[Foo](0), 10, version)

      reps.size === 10
      reps.head._2.vectorize === Array(1f, 1f)
      reps.last._2.vectorize === Array(10f, 10f)

      reps = retriever.getSince(SequenceNumber[Foo](490), 10, version)
      reps.size === 10
      reps.last._2.vectorize === Array(500f, 500f)

    }
  }
}
