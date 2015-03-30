package com.keepit.graph.wander

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ UriSparseLDAFeatures, DenseLDA, LDATopic, SparseTopicRepresentation }
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.manager._
import com.keepit.graph.simple.SimpleGraphTestModule
import com.keepit.graph.test.GraphTestInjector
import com.keepit.model._
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class URIWanderingTest extends Specification with URIWanderingTestHelper with GraphTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    SimpleGraphTestModule(),
    GraphCacheModule())

  "URIWanderingCommander" should {
    "work" in {
      withInjector(modules: _*) { implicit injector =>
        val manager = inject[GraphManager]
        val clock = inject[Clock]
        val wanderingCmdr = new WanderingCommander(manager, clock)
        val uriWanderingCmder = new URIWanderingCommander(manager, wanderingCmdr)
        manager.update(allUpdates: _*)
        val uriScores = Await.result(uriWanderingCmder.wander(Id[User](1), 10000), Duration(5, "seconds"))
        uriScores.keySet.map { _.id }.toList === List(1, 2) // uri 3 is not reachable
        uriScores(Id[NormalizedURI](1)) should be > 0
        uriScores(Id[NormalizedURI](2)) should be > 0
      }
    }
  }

}

trait URIWanderingTestHelper {

  /**
   *  u1 -- k1 -- uri1 -- topic1
   *  u2 -- k2 -- uri2 -- topic1
   *  u3 -- k3 -- uri3 -- topic2
   *  u1 - u2
   */
  def genLDAUpdate(uriId: Int, topicId: Int) = {
    val feat = SparseTopicRepresentation(dimension = 100, topics = Map(LDATopic(topicId) -> 1f))
    SparseLDAGraphUpdate(ModelVersion[DenseLDA](1), UriSparseLDAFeatures(uriId = Id[NormalizedURI](uriId), uriSeq = SequenceNumber[NormalizedURI](uriId), feat))
  }

  def genUserGraphUpdate(i: Int, firstName: String, lastName: String) = UserGraphUpdate(User(id = Some(Id[User](i)), firstName = firstName, lastName = lastName, seq = SequenceNumber(i), username = Username("test"), normalizedUsername = "test"))
  def genKeepUpdate(userId: Int, keepId: Int) = KeepGraphUpdate(Keep(id = Some(Id[Keep](keepId)), uriId = Id[NormalizedURI](keepId), urlId = Id[URL](keepId), url = "url" + keepId,
    userId = Id[User](userId), source = KeepSource("site"), seq = SequenceNumber(keepId), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](keepId)), inDisjointLib = true)) // libId == keepId (?)

  val userUpdate1 = genUserGraphUpdate(1, "Bei", "Liu")
  val userUpdate2 = genUserGraphUpdate(2, "Ming", "Kong")
  val userUpdate3 = genUserGraphUpdate(3, "Cao", "Cao")

  val userConnUpdate = UserConnectionGraphUpdate(Id[User](1), Id[User](2), UserConnectionStates.ACTIVE, SequenceNumber[UserConnection](1))

  val keepUpdate1 = genKeepUpdate(1, 1)
  val keepUpdate2 = genKeepUpdate(2, 2)
  val keepUpdate3 = genKeepUpdate(3, 3)

  val ldaUpdate1 = genLDAUpdate(1, 1)
  val ldaUpdate2 = genLDAUpdate(2, 1)
  val ldaUpdate3 = genLDAUpdate(3, 2)

  val allUpdates = List(userUpdate1, userUpdate2, userUpdate3, userConnUpdate, keepUpdate1, keepUpdate2, keepUpdate3, ldaUpdate1, ldaUpdate2, ldaUpdate3)
}
