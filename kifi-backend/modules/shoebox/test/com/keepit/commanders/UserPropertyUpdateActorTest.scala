package com.keepit.commanders

import com.keepit.common.actor.{ ActorInstance, FakeActorSystemModule, TestKitSupport }
import com.keepit.common.db.Id
import com.keepit.heimdal.{ ContextData, ContextDoubleData, FakeHeimdalServiceClientImpl, FakeHeimdalServiceClientModule, HeimdalServiceClient }
import com.keepit.model.User
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.collection.mutable.ArrayBuffer

class UserPropertyUpdateActorTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  def modules = FakeActorSystemModule() :: FakeHeimdalServiceClientModule() :: Nil

  implicit val propertyOrd = new Ordering[(String, ContextData)] {
    // used to test an array of these without worrying about ordering
    override def compare(x: (String, ContextData), y: (String, ContextData)): Int = x._1.compareTo(y._1)
  }

  "UserPropertyUpdateActor" should {

    "send user properties to heimdal" in withDb(modules: _*) { implicit injector =>
      val heimdalServiceClient = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
      val actorInstance = inject[ActorInstance[UserPropertyUpdateActor]]
      val (userId1, userId2, userId3) = (Id[User](1), Id[User](2), Id[User](3))

      actorInstance.ref ! userId1
      actorInstance.ref ! (userId2, UserPropertyUpdateInstruction.ConnectionCount)
      actorInstance.ref ! (userId3, UserPropertyUpdateInstruction.KeepCounts)
      actorInstance.ref ! (userId1, UserPropertyUpdateInstruction.TagCount)

      val zero = ContextDoubleData(0f)
      val keepsProps: ArrayBuffer[(String, ContextData)] = ArrayBuffer(
        ("keeps", zero),
        ("privateKeeps", zero),
        ("publicKeeps", zero))
      val connectionsProps = ArrayBuffer(("kifiConnections", zero))
      val tagsProps = ArrayBuffer(("tags", zero))

      val calls = heimdalServiceClient.setUserPropertyCalls
      calls.head._1 === userId1
      calls.head._2.sorted === (keepsProps ++ connectionsProps ++ tagsProps).sorted
      calls(1) === (userId2, connectionsProps)
      calls(2) === (userId3, keepsProps)
      calls(3) === (userId1, tagsProps)
    }
  }
}
