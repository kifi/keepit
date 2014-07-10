package com.keepit.scraper

import play.api.Logger
import com.keepit.common.healthcheck.AirbrakeNotifier

trait ScraperUtils {

  def airbrake: AirbrakeNotifier

  def formatErr(t: Throwable, tag: String, ctx: String) = s"[$tag] ($ctx) Caught exception (${t}); cause:${t.getCause}"
  def logErr(t: Throwable, tag: String, ctx: String, notify: Boolean = false)(implicit log: Logger): Unit = {
    val msg = formatErr(t, tag, ctx)
    log.error(msg, t)
    if (notify) airbrake.notify(msg, t)
  }

}
