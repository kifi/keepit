package com.keepit.heimdal

import org.specs2.mutable._

import com.keepit.common.db.LargeString._
import com.keepit.inject._
import com.keepit.test.TestInjector
import com.google.inject.Injector
import com.keepit.common.cache.HeimdalCacheModule
import com.keepit.common.time._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.heimdal.controllers.EventTrackingController

import play.api.test.Helpers._
import play.api.libs.json.{Json}

class EventTrackingTest extends Specification with TestInjector {

  def setup()(implicit injector: Injector) = {
    val eventTrackingController = inject[EventTrackingController]

    val testContext = UserEventContext(Map(
      "testField"->Seq(ContextStringData("Yay!"))
    ))
    val event = UserEvent(1, testContext, UserEventType("test_event"))

    val eventRepo = inject[UserEventLoggingRepo].asInstanceOf[TestUserEventLoggingRepo]

    (eventTrackingController, eventRepo, event)
  }

  "Event Tracking Controller" should {

    "store correctly" in {
      withInjector(TestMongoModule(), StandaloneTestActorSystemModule()) { implicit injector =>

        val (eventTrackingController, eventRepo, event) = setup()

        eventRepo.eventCount()===0
        eventTrackingController.trackInternalEvent(Json.toJson(event))
        eventRepo.eventCount()===1
        eventRepo.lastEvent.context.data("testField")(0).asInstanceOf[ContextStringData].value==="Yay!"

      }
    }

  }

}
