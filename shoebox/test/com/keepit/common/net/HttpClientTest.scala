package com.keepit.common.net

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification

class HttpClientTest extends Specification {

  "HttpClient" should {
    "instantiated" in {
      running(new ShoeboxApplication()) {
        val client = inject[HttpClient]
        client !== null
        client.longTimeout() !== null
      }
    }
  }
}
