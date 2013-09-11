package com.keepit.eliza

import play.modules.statsd.api.Statsd
import play.api.libs.json.JsValue

class EventStatsdInterceptor {
  def intercept(json: JsValue): Unit = {
    if ((json \ "eventName").as[String] == "dustSettled") {
      reportDustSettled(json \ "metaData")
    }
  }

  private def reportDustSettled(metaData: JsValue) = {
    val kifiReceivedAt = (metaData \ "kifiReceivedAt").as[Int]
    val kifiShownAt = (metaData \ "kifiShownAt").as[Int]
    val googleShownAt = (metaData \ "googleShownAt").as[Int]

    log.info(s"DustSettled: kifiReceivedAt: $kifiReceivedAt, kifiShownAt: $kifiShownAt, googleShownAt: $googleShownAt")

    Statsd.timing(s"$search.extension.kifiReceivedAt", kifiReceivedAt)
    Statsd.timing(s"$search.extension.kifiShownAt", kifiShownAt)
    Statsd.timing(s"$search.extension.googleShownAt", googleShownAt)

    Statsd.timing(s"$search.extension.kifiShownVsReceived", (kifiShownAt - kifiReceivedAt))

    Statsd.timing(s"$search.extension.kifiReceivedVsGoogle", (kifiReceivedAt - googleShownAt))
    Statsd.timing(s"$search.extension.kifiShownVsGoogle", (kifiShownAt - googleShownAt))

    //Adding 1000 to the numbers since they may be negative and mess up with statsd or graphite (negative timing may break things)
    //Testing if it makes any diferance
    Statsd.timing(s"$search.extension.kifiReceivedVsGoogle1000", (1000 + kifiReceivedAt - googleShownAt))
    Statsd.timing(s"$search.extension.kifiShownVsGoogle1000", (1000 + kifiShownAt - googleShownAt))
  }
}
