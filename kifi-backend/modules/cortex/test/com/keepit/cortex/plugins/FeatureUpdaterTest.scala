package com.keepit.cortex.plugins

import org.specs2.mutable.Specification
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.InMemoryCommitInfoStore
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.common.db.Id
import com.keepit.cortex.store.VersionedInMemoryStore
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.store.VersionedStore
import com.keepit.cortex.core.Versionable

class FeatureUpdaterTest extends Specification with FeaturePluginTestHelper {
  "feature updater" should {
    "keep track of commit data" in {

      val (fooRepresenter, fooFeatStore, commitStore, fakePuller) = setup()

      val updater = new FeatureUpdater(
        fooRepresenter, fooFeatStore, commitStore, fakePuller) {
        def getSeqNumber(foo: Foo) = SequenceNumber[Foo](foo.id.get.id)
        def genFeatureKey(foo: Foo) = foo.id.get
      }

      updater.commitInfo() === None
      updater.update()
      var info = updater.commitInfo().get
      info.seq.value === 500
      info.version.version === 1

      fooFeatStore.syncGet(Id[Foo](42), fooRepresenter.version).get.vectorize === Array(42f, 42f)
      fooFeatStore.syncGet(Id[Foo](550), fooRepresenter.version) === None

      // as if we restart server. should continue with last seqNum
      val updater2 = new FeatureUpdater(
        fooRepresenter, fooFeatStore, commitStore, fakePuller) {
        def getSeqNumber(foo: Foo) = SequenceNumber[Foo](foo.id.get.id)
        def genFeatureKey(foo: Foo) = foo.id.get
      }

      info = updater2.commitInfo().get
      info.seq.value === 500
      info.version.version === 1

      updater2.update()

      info = updater2.commitInfo().get
      info.seq.value === 550
      info.version.version === 1

      fooFeatStore.syncGet(Id[Foo](550), fooRepresenter.version).get.vectorize === Array(550f, 550f)

    }
  }

}
