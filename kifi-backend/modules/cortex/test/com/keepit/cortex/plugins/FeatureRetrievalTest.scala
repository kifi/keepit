package com.keepit.cortex.plugins

import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber

class FeatureRetrievalTest extends Specification with FeaturePluginTestHelper {
  "feature retrieval" should {
    "work" in {

      val (fooRepresenter, fooFeatStore, commitStore, fakePuller) = setup()

      val updater = new FeatureUpdater(
        fooRepresenter, fooFeatStore, commitStore, fakePuller) {
        def getSeqNumber(foo: Foo) = SequenceNumber[Foo](foo.id.get.id)
        def genFeatureKey(foo: Foo) = foo.id.get
      }

      updater.update()

      val retriever = new FeatureRetrieval(
        fooFeatStore, commitStore, fakePuller
      ) {
        def genFeatureKey(foo: Foo) = foo.id.get
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

    "tricky retrieval works" in {
      val (fooRepresenter, fooOddFeatStore, commitStore, fakePuller) = setup2()

      val updater = new FeatureUpdater(
        fooRepresenter, fooOddFeatStore, commitStore, fakePuller) {
        def getSeqNumber(foo: Foo) = SequenceNumber[Foo](foo.id.get.id)
        def genFeatureKey(foo: Foo) = foo.id.get
      }

      updater.update()

      val retriever = new FeatureRetrieval(
        fooOddFeatStore, commitStore, fakePuller
      ) {
        def genFeatureKey(foo: Foo) = foo.id.get
      }

      val version = fooRepresenter.version

      // tricky version is better
      var reps = retriever.getSince(SequenceNumber[Foo](0), 10, version)
      reps.size === 4
      reps.map { case (foo, _) => foo.id.get.id } === Range(1, 13, 3)

      reps = retriever.trickyGetSince(SequenceNumber[Foo](0), 10, version)
      reps.size === 10
      reps.map { case (foo, _) => foo.id.get.id } === Range(1, 30, 3)

      // exhausted
      reps = retriever.getSince(SequenceNumber[Foo](450), 100, version)
      reps.size === 17
      reps.map { case (foo, _) => foo.id.get.id } === Range(451, 500, 3)

      reps = retriever.trickyGetSince(SequenceNumber[Foo](450), 100, version)
      reps.size === 17
      reps.map { case (foo, _) => foo.id.get.id } === Range(451, 500, 3)
    }
  }
}
