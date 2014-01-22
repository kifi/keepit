package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.scraper.ScraperConfig
import org.joda.time.DateTime
import scala.math._
import com.keepit.common.logging.Logging

object ScrapeInfoStates extends States[ScrapeInfo] {
  val PENDING = State[ScrapeInfo]("pending") // scheduled
}

case class ScrapeInfo(
  id: Option[Id[ScrapeInfo]] = None,
  uriId: Id[NormalizedURI], // = NormalizedURI id
  lastScrape: DateTime = START_OF_TIME,
  nextScrape: DateTime = START_OF_TIME,
  interval: Double = 24.0d, // hours
  failures: Int = 0,
  state: State[ScrapeInfo] = ScrapeInfoStates.ACTIVE,
  signature: String = "",
  destinationUrl: Option[String] = None
) extends ModelWithState[ScrapeInfo] with Logging {
  def withId(id: Id[ScrapeInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this
  def withState(state: State[ScrapeInfo]) = {
    log.debug(s"[withState($id, $uriId, $destinationUrl)] ${this.state} => ${state.toString.toUpperCase}; nextScrape(not set)=${this.nextScrape}")
    this.copy(state = state)
  }

  def withStateAndNextScrape(state: State[ScrapeInfo]) = {
    val (curState, curNS) = (this.state, this.nextScrape)
    val res = state match { // TODO: revisit
      case ScrapeInfoStates.ACTIVE => copy(state = state, nextScrape = START_OF_TIME) // scrape ASAP when switched to ACTIVE
      case ScrapeInfoStates.INACTIVE => copy(state = state, nextScrape = END_OF_TIME) // never scrape when switched to INACTIVE
      case ScrapeInfoStates.PENDING => copy(state = state, nextScrape = currentDateTime) // TODO: add & use updatedAt
    }
    log.debug(s"[withStateAndNextScrape($id, $uriId, $destinationUrl)] ${curState} => ${res.state.toString.toUpperCase}; ${curNS} => ${res.nextScrape}")
    res
  }

  def withDestinationUrl(destinationUrl: Option[String]) = copy(destinationUrl = destinationUrl)

  def withFailure()(implicit config: ScraperConfig) = {
    val backoff = min(config.maxBackoff, (config.initialBackoff * (1 << failures).toDouble))
    val newInterval = min(config.maxInterval, (interval + config.intervalIncrement))
    val now = currentDateTime
    copy(nextScrape = now.plusSeconds(hoursToSeconds(backoff) + config.randomDelay),
         interval = newInterval,
         failures = this.failures + 1)
  }

  def withDocumentUnchanged()(implicit config: ScraperConfig) = {
    val newInterval = min(config.maxInterval, (interval + config.intervalIncrement))
    val now = currentDateTime
    copy(nextScrape = now.plusSeconds(hoursToSeconds(newInterval) + config.randomDelay),
         interval = newInterval,
         failures = 0)
  }

  def withDocumentChanged(newSignature: String)(implicit config: ScraperConfig) = {
    val newInterval = max(config.minInterval, interval - config.intervalDecrement)
    val now = currentDateTime
    copy(lastScrape = now,
         nextScrape = now.plusSeconds(hoursToSeconds(newInterval) + config.randomDelay),
         interval = newInterval,
         failures = 0,
         signature = newSignature)
  }

  private[this] def hoursToSeconds(hours: Double) = (hours * 60.0d * 60.0d).toInt
  def withNextScrape(nextScrape: DateTime) = copy(nextScrape = nextScrape)

  override def toString = s"[ScrapeInfo(id=$id, uriId=$uriId): state=$state, lastScrape=$lastScrape, nextScrape=$nextScrape, interval=$interval, failures=$failures, dstUrl=$destinationUrl]"
}

object ScrapeInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[ScrapeInfo]) and
      (__ \ 'uriId).format(Id.format[NormalizedURI]) and
      (__ \ 'lastScrape).format[DateTime] and
      (__ \ 'nextScrape).format[DateTime] and
      (__ \ 'interval).format[Double] and
      (__ \ 'failures).format[Int] and
      (__ \ 'state).format(State.format[ScrapeInfo]) and
      (__ \ 'signature).format[String] and
      (__ \ 'destinationUrl).formatNullable[String]
    )(ScrapeInfo.apply, unlift(ScrapeInfo.unapply))
}


