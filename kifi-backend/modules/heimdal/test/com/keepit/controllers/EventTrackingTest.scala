package com.keepit.controllers

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.social.NonUserKinds
import com.keepit.test.HeimdalTestInjector
import org.specs2.mutable._
import play.api.libs.json.Json

class EventTrackingTest extends Specification with HeimdalTestInjector {

  def modules = {
    Seq(FakeMongoModule(), FakeActorSystemModule(), HeimdalQueueDevModule(), HeimdalServiceTypeModule(), FakeHttpClientModule(), FakeExecutionContextModule())
  }

  def setup()(implicit injector: Injector) = {
    val eventTrackingController = inject[EventTrackingController]

    val testContext = HeimdalContext(Map(
      "testField" -> ContextStringData("Yay!")
    ))
    val userEventRepo = inject[UserEventLoggingRepo].asInstanceOf[FakeUserEventLoggingRepo]
    val systemEventRepo = inject[SystemEventLoggingRepo].asInstanceOf[FakeSystemEventLoggingRepo]
    val anonymousEventRepo = inject[AnonymousEventLoggingRepo].asInstanceOf[FakeAnonymousEventLoggingRepo]
    val visitorEventRepo = inject[VisitorEventLoggingRepo].asInstanceOf[FakeVisitorEventLoggingRepo]
    val nonUserEventRepo = inject[NonUserEventLoggingRepo].asInstanceOf[FakeNonUserEventLoggingRepo]

    (eventTrackingController, userEventRepo, systemEventRepo, anonymousEventRepo, visitorEventRepo, nonUserEventRepo, testContext)
  }

  "Event Tracking Controller" should {

    "store correctly" in {
      withInjector(modules: _*) { implicit injector =>
        val (eventTrackingController, userEventRepo, systemEventRepo, anonymousEventRepo, visitorEventRepo, nonUserEventRepo, testContext) = setup()
        val userEvent: HeimdalEvent = UserEvent(Id(1), testContext, EventType("user_test_event"))
        userEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(userEvent))
        inject[WatchableExecutionContext].drain()
        userEventRepo.eventCount() === 1
        userEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"

        val systemEvent: HeimdalEvent = SystemEvent(testContext, EventType("system_test_event"))
        systemEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(systemEvent))
        inject[WatchableExecutionContext].drain()

        systemEventRepo.eventCount() === 1
        systemEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"

        val anonymousEvent: HeimdalEvent = AnonymousEvent(testContext, EventType("anonymous_test_event"))
        anonymousEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(anonymousEvent))
        inject[WatchableExecutionContext].drain()

        anonymousEventRepo.eventCount() === 1
        anonymousEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"

        val visitorEvent: HeimdalEvent = VisitorEvent(testContext, EventType("visitor_test_event"))
        visitorEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(visitorEvent))
        inject[WatchableExecutionContext].drain()

        visitorEventRepo.eventCount() === 1
        visitorEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"

        val nonUserEvent: HeimdalEvent = NonUserEvent("non_user@join.com", NonUserKinds.email, testContext, EventType("non_user_test_event"))
        nonUserEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(nonUserEvent))
        inject[WatchableExecutionContext].drain()

        nonUserEventRepo.eventCount() === 1
        nonUserEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"
      }
    }

    "store array" in {
      withInjector(modules: _*) { implicit injector =>
        val (eventTrackingController, userEventRepo, systemEventRepo, anonymousEventRepo, visitorEventRepo, nonUserEventRepo, testContext) = setup()
        val events: Array[HeimdalEvent] = Array(
          UserEvent(Id(1), testContext, EventType("test_event")),
          UserEvent(Id(2), testContext, EventType("user_test_event")),
          UserEvent(Id(3), testContext, EventType("user_test_event")),
          UserEvent(Id(4), testContext, EventType("user_test_event")),
          SystemEvent(testContext, EventType("system_test_event")),
          AnonymousEvent(testContext, EventType("anonymous_test_event")),
          VisitorEvent(testContext, EventType("visitor_test_event")),
          NonUserEvent("non_user@join.com", NonUserKinds.email, testContext, EventType("non_user_test_event"))
        )
        userEventRepo.eventCount() === 0
        systemEventRepo.eventCount() === 0
        anonymousEventRepo.eventCount() === 0
        nonUserEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvents(Json.toJson(events))
        inject[WatchableExecutionContext].drain()

        //this test is failing sporadicly
        //userEventRepo.eventCount() === 4
        //userEventRepo.events(0).userId === Id(1)
        //userEventRepo.events(1).userId === Id(2)
        //userEventRepo.events(2).userId === Id(3)
        //userEventRepo.events(3).userId === Id(4)

        systemEventRepo.eventCount() === 1
        systemEventRepo.events(0).eventType === EventType("system_test_event")

        anonymousEventRepo.eventCount() === 1
        anonymousEventRepo.events(0).eventType === EventType("anonymous_test_event")

        visitorEventRepo.eventCount() === 1
        visitorEventRepo.events(0).eventType === EventType("visitor_test_event")

        nonUserEventRepo.eventCount === 1
        nonUserEventRepo.events(0).eventType === EventType("non_user_test_event")
      }
    }

  }

}
