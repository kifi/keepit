package com.keepit.common.geckoboard

import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.net._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.google.inject._
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor.Scheduler
import org.joda.time.DateTime
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import play.api.libs.json._

// http://www.geckoboard.com/developers/custom-widgets/push/

object GeckoboardPublisher {
  val apiKey = "68783556cbbbd939dc667b4ea449d12c"
  val pushUri = "https://push.geckoboard.com/v1/send/"
}

@ImplementedBy(classOf[GeckoboardPublisherImpl])
trait GeckoboardPublisher {
  def publish(data: GeckoboardData[_]): Unit
}

class GeckoboardPublisherImpl @Inject() (httpClient: HttpClient)
    extends GeckoboardPublisher with Logging {
  import GeckoboardPublisher._

  def publish(data: GeckoboardData[_]): Unit = {
    val obj = Json.obj("api_key" -> apiKey, "data" -> data.json)
    val url = pushUri + data.widget.id.id
    httpClient.postFuture(url, obj) map {res =>
      assume(res.body == """{"success":true}""")
    }
  }
}
