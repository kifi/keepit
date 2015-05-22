package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.logging.Logging

object ScrapeInfoStates extends States[ScrapeInfo] {
  val ASSIGNED = State[ScrapeInfo]("assigned") // pull
}

class ScraperWorker()

case class ScrapeInfo(
    id: Option[Id[ScrapeInfo]] = None,
    uriId: Id[NormalizedURI], // = NormalizedURI id
    workerId: Option[Id[ScraperWorker]] = None,
    lastScrape: DateTime = START_OF_TIME,
    nextScrape: DateTime = START_OF_TIME,
    interval: Double = 24.0d, // hours
    failures: Int = 0,
    state: State[ScrapeInfo] = ScrapeInfoStates.ACTIVE,
    signature: String = "",
    destinationUrl: Option[String] = None) extends ModelWithState[ScrapeInfo] with Logging {
  def withId(id: Id[ScrapeInfo]) = this.copy(id = Some(id))
  def withWorkerId(id: Id[ScraperWorker]) = this.copy(workerId = Some(id))
  def withUpdateTime(now: DateTime) = this
  def withState(state: State[ScrapeInfo]) = {
    if (state == ScrapeInfoStates.INACTIVE) throw new IllegalArgumentException("use withStateAndNextScrape to set the state to INACTIVE")
    log.debug(s"[withState($id, $uriId, $workerId, $destinationUrl)] ${this.state} => ${state.toString.toUpperCase}; nextScrape(not set)=${this.nextScrape}")
    this.copy(state = state)
  }

  def withStateAndNextScrape(state: State[ScrapeInfo]) = {
    import ScrapeInfoStates._
    val (curState, curNS) = (this.state, this.nextScrape)
    val res = state match { // TODO: revisit
      case ACTIVE => copy(state = state, nextScrape = START_OF_TIME) // scrape ASAP when switched to ACTIVE
      case INACTIVE => copy(state = state, nextScrape = END_OF_TIME) // never scrape when switched to INACTIVE
      case ASSIGNED => copy(state = state, nextScrape = currentDateTime)
    }
    if (curState == INACTIVE && res.state == ACTIVE || curState == ACTIVE && res.state == INACTIVE)
      log.warn(s"[withStateAndNextScrape($id, $uriId, ${destinationUrl.toString.take(50)})] ${curState.toString.toUpperCase} => ${res.state.toString.toUpperCase}; $curNS => ${res.nextScrape}")
    res
  }

  def withDestinationUrl(destinationUrl: Option[String]) = copy(destinationUrl = destinationUrl)

  private[this] def hoursToSeconds(hours: Double) = (hours * 60.0d * 60.0d).toInt
  def withNextScrape(nextScrape: DateTime) = copy(nextScrape = nextScrape)

  override def toString = s"[ScrapeInfo(id=$id, uriId=$uriId, worker=$workerId): state=$state, lastScrape=$lastScrape, nextScrape=$nextScrape, interval=$interval, failures=$failures, dstUrl=$destinationUrl]"
  def toShortString = s"ScrapeInfo($id,$uriId,$workerId,$state,$nextScrape,${destinationUrl.take(50)})"
}

object ScrapeInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ScrapeInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'workerId).formatNullable(Id.format[ScraperWorker]) and
    (__ \ 'lastScrape).format[DateTime] and
    (__ \ 'nextScrape).format[DateTime] and
    (__ \ 'interval).format[Double] and
    (__ \ 'failures).format[Int] and
    (__ \ 'state).format(State.format[ScrapeInfo]) and
    (__ \ 'signature).format[String] and
    (__ \ 'destinationUrl).formatNullable[String]
  )(ScrapeInfo.apply, unlift(ScrapeInfo.unapply))
}

