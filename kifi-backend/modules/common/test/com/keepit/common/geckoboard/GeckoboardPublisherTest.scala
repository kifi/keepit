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
import WidgetType.NumberAndSecondaryStatType

// http://www.geckoboard.com/developers/custom-widgets/push/

class GeckoboardPublisherTest extends Specification with TestInjector {

  "GeckoboardPublisher" should {

    "serialize" in {
      val data = NumberAndSecondaryStat(GeckoboardWidget.TotalKeepsPerHour, 10, 15)
      data.json.toString === "replace me"
    }
  }
}
