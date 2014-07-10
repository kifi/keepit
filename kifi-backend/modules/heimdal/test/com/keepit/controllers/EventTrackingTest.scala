package com.keepit.controllers

import com.keepit.model._
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
import play.api.libs.json.{ Json }
import com.keepit.common.db.Id
import akka.actor.ActorSystem
import com.keepit.social.NonUserKinds

class EventTrackingTest extends Specification with TestInjector {

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(TestMongoModule(), StandaloneTestActorSystemModule(), HeimdalQueueDevModule())
  }

  def setup()(implicit injector: Injector) = {
    val eventTrackingController = inject[EventTrackingController]

    val testContext = HeimdalContext(Map(
      "testField" -> ContextStringData("Yay!")
    ))
    val userEventRepo = inject[UserEventLoggingRepo].asInstanceOf[TestUserEventLoggingRepo]
    val systemEventRepo = inject[SystemEventLoggingRepo].asInstanceOf[TestSystemEventLoggingRepo]
    val anonymousEventRepo = inject[AnonymousEventLoggingRepo].asInstanceOf[TestAnonymousEventLoggingRepo]
    val nonUserEventRepo = inject[NonUserEventLoggingRepo].asInstanceOf[TestNonUserEventLoggingRepo]

    (eventTrackingController, userEventRepo, systemEventRepo, anonymousEventRepo, nonUserEventRepo, testContext)
  }

  "Event Tracking Controller" should {

    "store correctly" in {
      withInjector(modules: _*) { implicit injector =>
        val (eventTrackingController, userEventRepo, systemEventRepo, anonymousEventRepo, nonUserEventRepo, testContext) = setup()
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

        val anonymousEvent: HeimdalEvent = AnonymousEvent(testContext, EventType("anonymous_test_event"))
        anonymousEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(anonymousEvent))
        anonymousEventRepo.eventCount() === 1
        anonymousEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"

        val nonUserEvent: HeimdalEvent = NonUserEvent("non_user@join.com", NonUserKinds.email, testContext, EventType("non_user_test_event"))
        nonUserEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvent(Json.toJson(nonUserEvent))
        nonUserEventRepo.eventCount() === 1
        nonUserEventRepo.lastEvent.context.data("testField").asInstanceOf[ContextStringData].value === "Yay!"
      }
    }

    "store array" in {
      withInjector(modules: _*) { implicit injector =>
        val (eventTrackingController, userEventRepo, systemEventRepo, anonymousEventRepo, nonUserEventRepo, testContext) = setup()
        val events: Array[HeimdalEvent] = Array(
          UserEvent(Id(1), testContext, EventType("test_event")),
          UserEvent(Id(2), testContext, EventType("user_test_event")),
          UserEvent(Id(3), testContext, EventType("user_test_event")),
          UserEvent(Id(4), testContext, EventType("user_test_event")),
          SystemEvent(testContext, EventType("system_test_event")),
          AnonymousEvent(testContext, EventType("anonymous_test_event")),
          NonUserEvent("non_user@join.com", NonUserKinds.email, testContext, EventType("non_user_test_event"))
        )
        userEventRepo.eventCount() === 0
        systemEventRepo.eventCount() === 0
        anonymousEventRepo.eventCount() === 0
        nonUserEventRepo.eventCount() === 0
        eventTrackingController.trackInternalEvents(Json.toJson(events))

        userEventRepo.eventCount() === 4
        userEventRepo.events(0).userId === Id(1)
        userEventRepo.events(1).userId === Id(2)
        userEventRepo.events(2).userId === Id(3)
        userEventRepo.events(3).userId === Id(4)

        systemEventRepo.eventCount() === 1
        systemEventRepo.events(0).eventType === EventType("system_test_event")

        anonymousEventRepo.eventCount() === 1
        anonymousEventRepo.events(0).eventType === EventType("anonymous_test_event")

        nonUserEventRepo.eventCount === 1
        nonUserEventRepo.events(0).eventType === EventType("non_user_test_event")
      }
    }

  }

}
