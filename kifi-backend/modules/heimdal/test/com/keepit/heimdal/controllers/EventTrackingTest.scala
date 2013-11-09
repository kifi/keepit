package com.keepit.heimdal.controllers

import org.specs2.mutable._

import com.keepit.common.db.LargeString._
import com.keepit.inject._
import com.keepit.test.TestInjector
import com.google.inject.Injector
import com.keepit.common.cache.HeimdalCacheModule
import com.keepit.common.time._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.heimdal._

import play.api.test.Helpers._
import play.api.libs.json.{Json}

class EventTrackingTest extends Specification with TestInjector {

  def setup()(implicit injector: Injector) = {
    val eventTrackingController = inject[EventTrackingController]

    val testContext = EventContext(Map(
      "testField" -> Seq(ContextStringData("Yay!"))
    ))
    val eventRepo = inject[UserEventLoggingRepo].asInstanceOf[TestUserEventLoggingRepo]

    (eventTrackingController, eventRepo, testContext)
  }

  "Event Tracking Controller" should {

    "store correctly" in {
      withInjector(TestMongoModule(), StandaloneTestActorSystemModule()) { implicit injector =>
        val (eventTrackingController, eventRepo, testContext) = setup()
        val event: HeimdalEvent = UserEvent(1, testContext, EventType("test_event"))
        eventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(event))
        eventRepo.eventCount() === 1
        eventRepo.lastEvent.context.data("testField")(0).asInstanceOf[ContextStringData].value === "Yay!"

      }
    }

    "store array" in {
      withInjector(TestMongoModule(), StandaloneTestActorSystemModule()) { implicit injector =>
        val (eventTrackingController, eventRepo, testContext) = setup()
        val events: Array[HeimdalEvent] = Array( UserEvent(1, testContext, EventType("test_event")),
                            UserEvent(2, testContext, EventType("test_event")),
                            UserEvent(3, testContext, EventType("test_event")),
                            UserEvent(4, testContext, EventType("test_event")))
        eventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvents(Json.toJson(events))
        eventRepo.eventCount() === 4
        eventRepo.events(0).userId === 1
        eventRepo.events(1).userId === 2
        eventRepo.events(2).userId === 3
        eventRepo.events(3).userId === 4

      }
    }

  }

}
