package com.keepit.heimdal

import org.specs2.mutable.Specification
import com.keepit.shoebox.{FakeShoeboxServiceClientImpl, ShoeboxServiceClient}
import scala.concurrent.{Await, Future}
import com.keepit.model.Gender
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.akka.TestKitScope
import scala.concurrent.duration._
import akka.actor.ActorSystem

class UserEventAugmentorsTest extends TestKitScope(ActorSystem("test-actor-system")) with Specification {
  val airbrake = new FakeAirbrakeNotifier()
  val shoeboxClient = new FakeShoeboxServiceClientImpl(airbrake)

  val userIdAugmentor = UserIdAugmentor
  val userValuesAugmentor = new UserValuesAugmentor(shoeboxClient)
  val versionAugmentor = new ExtensionVersionAugmentor(shoeboxClient)
  val failingAugmentor = new EventAugmentor[UserEvent] {
    def isDefinedAt(event: UserEvent) = true
    def apply(event: UserEvent) = Future.failed(new Exception("I fail therefore I am."))
  }

  shoeboxClient.setUserValue(Id(134), "gender", "male")

  "UserIdAugmentor" should {
    "Augment event with userId" in {
      val event = new UserEvent(Id(134), new HeimdalContext(Map("name" -> ContextStringData("Léo"))), EventType("fake"))
      Await.result(userIdAugmentor(event), 1 seconds) === Seq("gender" -> ContextStringData("male"))
    }
  }
}
