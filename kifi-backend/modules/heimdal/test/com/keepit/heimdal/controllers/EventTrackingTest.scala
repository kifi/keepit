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
import com.keepit.common.db.Id

class EventTrackingTest extends Specification with TestInjector {

  def setup()(implicit injector: Injector) = {
    val eventTrackingController = inject[EventTrackingController]

    val testContext = HeimdalContext(Map(
      "testField" -> ContextStringData("Yay!")
    ))
    val userEventRepo = inject[UserEventLoggingRepo].asInstanceOf[TestUserEventLoggingRepo]
    val systemEventRepo = inject[SystemEventLoggingRepo].asInstanceOf[TestSystemEventLoggingRepo]

    (eventTrackingController, userEventRepo, systemEventRepo, testContext)
  }

  "Event Tracking Controller" should {

    "store correctly" in {
      withInjector(TestMongoModule(), StandaloneTestActorSystemModule()) { implicit injector =>
        val (eventTrackingController, userEventRepo, systemEventRepo, testContext) = setup()
        val userEvent: HeimdalEvent = UserEvent(Id(1), testContext, EventType("user_test_event"))
        userEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(userEvent))
        userEventRepo.eventCount() === 1
        userEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"

        val systemEvent: HeimdalEvent = SystemEvent(testContext, EventType("system_test_event"))
        systemEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(systemEvent))
        systemEventRepo.eventCount() === 1
        systemEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"
      }
    }

    "store array" in {
      withInjector(TestMongoModule(), StandaloneTestActorSystemModule()) { implicit injector =>
        val (eventTrackingController, userEventRepo, systemEventRepo, testContext) = setup()
        val events: Array[HeimdalEvent] = Array( UserEvent(Id(1), testContext, EventType("test_event")),
                            UserEvent(Id(2), testContext, EventType("user_test_event")),
                            UserEvent(Id(3), testContext, EventType("user_test_event")),
                            UserEvent(Id(4), testContext, EventType("user_test_event")),
                            SystemEvent(testContext, EventType("system_test_event")))
        userEventRepo.eventCount() === 0
        systemEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvents(Json.toJson(events))

        userEventRepo.eventCount() === 4
        userEventRepo.events(0).userId === Id(1)
        userEventRepo.events(1).userId === Id(2)
        userEventRepo.events(2).userId === Id(3)
        userEventRepo.events(3).userId === Id(4)

        systemEventRepo.eventCount() === 1
        systemEventRepo.events(0).eventType === EventType("system_test_event")
      }
    }

  }

}
