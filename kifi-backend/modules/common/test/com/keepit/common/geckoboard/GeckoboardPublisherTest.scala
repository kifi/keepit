package com.keepit.common.geckoboard

import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.net._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.test._
import com.google.inject._
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor.Scheduler
import org.joda.time.DateTime
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import org.specs2.mutable.Specification
import play.api.libs.json._

// http://www.geckoboard.com/developers/custom-widgets/push/
class GeckoboardPublisherTest extends Specification with TestInjector {

  "GeckoboardPublisher" should {

    "serialize" in {
      val data = NumberAndSecondaryStat(10, 15)
      data.json.toString === """{"item":[{"text":"","value":10},{"text":"","value":15}]}"""
    }

    "send" in {
      class MyWidget()
          extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb")) {

        def data(): NumberAndSecondaryStat = NumberAndSecondaryStat(10, 15)
      }

      val client = new FakeHttpClient() {
        override def post(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = {
          url.url === "https://push.geckoboard.com/v1/send/37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb"
          body.toString === """{"api_key":"68783556cbbbd939dc667b4ea449d12c","data":{"item":[{"text":"","value":10},{"text":"","value":15}]}}"""
          FakeClientResponse("""{"success":true}""")
        }
      }
      val publisher = new GeckoboardPublisherImpl(client)
      publisher.publish(new MyWidget())
      1 === 1
    }
  }
}
