package com.keepit.graph.wander

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, SparseTopicRepresentation, UriSparseLDAFeatures, DenseLDA }
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.manager.{ GraphManager, SparseLDAGraphUpdate, KeepGraphUpdate, UserGraphUpdate }
import com.keepit.graph.simple.SimpleGraphTestModule
import com.keepit.graph.test.GraphTestInjector
import com.keepit.model._
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FeedExplanationCommanderTest extends Specification with GraphTestInjector with FeedHelper {

  val modules = Seq(
    SimpleGraphTestModule(),
    GraphCacheModule())

  "feed explain commander" should {
    "almost always correctly explains feed" in {
      withInjector(modules: _*) { implicit injector =>
        val manager = inject[GraphManager]
        val clock = inject[Clock]
        val commander = new FeedExplanationCommander(manager, clock)

        manager.update(allUpdates: _*)
        val explains = Await.result(commander.explain(Id[User](1), Seq(Id[NormalizedURI](3))), Duration(5, "seconds"))
        val keepScores = explains.head.keepScores
        keepScores(Id[Keep](1)) should be > 0 // keepScores(Id[Keep](2)). need fixed random number seed to make this consistent.
      }
    }
  }
}

trait FeedHelper {
  // u1 -- k1 -- uri1 -- topic1 -- uri3 == recommend_uri
  //  `-- k2 -- uri2 -- topic2 -- many other uris in topic 2
  // expect: k1 get higher score

  def genLDAUpdate(uriId: Int, topicId: Int) = {
    val feat = SparseTopicRepresentation(dimension = 100, topics = Map(LDATopic(topicId) -> 1f))
    SparseLDAGraphUpdate(ModelVersion[DenseLDA](1), UriSparseLDAFeatures(uriId = Id[NormalizedURI](uriId), uriSeq = SequenceNumber[NormalizedURI](uriId), feat))
  }

  val userUpdate = UserGraphUpdate(UserFactory.user().withId(1).withName("Dummy", "Yummy").withUsername("test").withSeq(1).get)
  val keepUpdate1 = KeepGraphUpdate(KeepFactory.keep().withId(Id[Keep](1)).withURIId(Id[NormalizedURI](1)).withUrl("url1")
    .withUser(Id[User](1)).withSource(KeepSource.Site).withSeq(SequenceNumber(1)).withLibraryId((Id[Library](1), LibraryVisibility.DISCOVERABLE, None)).get)
  val keepUpdate2 = KeepGraphUpdate(KeepFactory.keep().withId(Id[Keep](2)).withURIId(Id[NormalizedURI](2)).withUrl("url2")
    .withUser(Id[User](1)).withSource(KeepSource.Site).withSeq(SequenceNumber(2)).withLibraryId((Id[Library](2), LibraryVisibility.DISCOVERABLE, None)).get)

  val ldaUpdate1 = genLDAUpdate(1, 1)
  val ldaUpdate2 = genLDAUpdate(3, 1)
  val ldaUpdate3 = genLDAUpdate(2, 2)
  val ldaUpdates = (4 to 100).map { i => genLDAUpdate(i, 2) }

  val allUpdates = List(userUpdate, keepUpdate1, keepUpdate2, ldaUpdate1, ldaUpdate2, ldaUpdate3) ++ ldaUpdates

}
