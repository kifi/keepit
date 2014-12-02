package com.keepit.heimdal

import com.keepit.model._
import org.specs2.mutable.Specification
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import scala.concurrent.{ Await, Future }
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.common.db.{ ExternalId, Id }
import scala.concurrent.duration._
import akka.actor.ActorSystem
import com.keepit.akka.FutureTestScope
import com.keepit.common.crypto.PublicIdConfiguration

class UserEventAugmentorsTest extends Specification with FutureTestScope {
  val airbrake = new FakeAirbrakeNotifier()
  val shoeboxClient = new FakeShoeboxServiceClientImpl(airbrake, PublicIdConfiguration("whatever"))

  val userIdAugmentor = UserIdAugmentor
  val userValuesAugmentor = new UserValuesAugmentor(shoeboxClient)
  val versionAugmentor = new ExtensionVersionAugmentor(shoeboxClient)
  val failingAugmentor = new EventAugmentor[UserEvent] {
    def isDefinedAt(event: UserEvent) = true
    def apply(event: UserEvent) = Future.failed(new Exception("I fail therefore I am."))
  }

  "UserIdAugmentor" should {
    "Augment event with userId" in {
      val event = new UserEvent(Id(134), new HeimdalContext(Map("name" -> ContextStringData("Léo"))), EventType("fake"))
      Await.result(userIdAugmentor(event), 1 seconds) === Seq("userId" -> ContextDoubleData(134))
    }
  }

  "UserValuesAugmentor" should {
    "Augment event with gender when it's available" in {
      val event = new UserEvent(Id(134), new HeimdalContext(Map("name" -> ContextStringData("Léo"))), EventType("fake"))
      Await.result(userValuesAugmentor(event), 1 seconds) === Seq.empty
      shoeboxClient.setUserValue(Id(134), UserValueName.GENDER, "male")
      Await.result(userValuesAugmentor(event), 1 seconds) === Seq("gender" -> ContextStringData(Gender.Male.toString))
    }
  }

  "ExtensionVersionAugmentor" should {
    "Not be applicable if the version is already there" in {
      val contextWithVersion = new HeimdalContext(Map("name" -> ContextStringData("Léo"), "extensionVersion" -> ContextStringData("dummy")))
      val event = new UserEvent(Id(134), contextWithVersion, EventType("fake"))
      versionAugmentor.isDefinedAt(event) === false
    }

    "Not augment event with version if an installation id is not available" in {
      val contextWithoutVersionNorInstallationId = new HeimdalContext(Map("name" -> ContextStringData("Léo")))
      val event = new UserEvent(Id(134), contextWithoutVersionNorInstallationId, EventType("fake"))
      Await.result(versionAugmentor(event), 1 seconds) === Seq.empty
    }

    "Augment event with version if it's not there" in {
      val contextWithoutVersion = new HeimdalContext(Map(
        "name" -> ContextStringData("Léo"),
        "kifiInstallationId" -> ContextStringData(ExternalId[KifiInstallation]().id))
      )
      val event = new UserEvent(Id(134), contextWithoutVersion, EventType("fake"))
      Await.result(versionAugmentor(event), 1 seconds) === Seq("extensionVersion" -> ContextStringData("dummy"))
    }

    "Augment event with version if it's there but empty" in {
      val contextWithEmptyVersion = new HeimdalContext(Map(
        "name" -> ContextStringData("Léo"),
        "kifiInstallationId" -> ContextStringData(ExternalId[KifiInstallation]().id),
        "extensionVersion" -> ContextStringData(""))
      )
      val event = new UserEvent(Id(134), contextWithEmptyVersion, EventType("fake"))
      Await.result(versionAugmentor(event), 1 seconds) === Seq("extensionVersion" -> ContextStringData("dummy"))
    }
  }

  "EventAugmentor" should {
    "apply multiple augmentors to an event and deal with failures" in {
      val initialData = Map("name" -> ContextStringData("Léo"), "kifiInstallationId" -> ContextStringData(ExternalId[KifiInstallation]().id))
      val contextWithoutVersion = new HeimdalContext(initialData)
      val event = new UserEvent(Id(134), contextWithoutVersion, EventType("fake"))
      val updatedContext = Await.result(EventAugmentor.safelyAugmentContext(event, userIdAugmentor, userValuesAugmentor, versionAugmentor, failingAugmentor), 1 second)
      updatedContext.data === initialData ++ Map("gender" -> ContextStringData(Gender.Male.toString), "extensionVersion" -> ContextStringData("dummy"), "userId" -> ContextDoubleData(134))
    }
  }
}
