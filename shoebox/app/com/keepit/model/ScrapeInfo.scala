package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import com.keepit.scraper.ScraperConfig
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import play.api.libs.json._
import scala.math._

case class ScrapeInfo(
  id: Option[Id[ScrapeInfo]] = None,
  uriId: Id[NormalizedURI], // = NormalizedURI id
  lastScrape: DateTime = currentDateTime,
  nextScrape: DateTime = currentDateTime,
  interval: Double = 24.0d, // hours
  failures: Int = 0,
  state: State[ScrapeInfo] = ScrapeInfoStates.ACTIVE,
  signature: String = "",
  destinationUrl: Option[String] = None
) {

  def withState(state: State[ScrapeInfo]) = {
    state match {
      case ScrapeInfoStates.ACTIVE => copy(state = state, nextScrape = currentDateTime) // scrape ASAP when switched to ACTIVE
      case ScrapeInfoStates.INACTIVE => copy(state = state, nextScrape = END_OF_TIME) // never scrape when switched to INACTIVE
    }
  }

  def withDestinationUrl(destinationUrl: Option[String]) = {
   copy(destinationUrl = destinationUrl)
  }

  def withFailure()(implicit config: ScraperConfig) = {
    val backoff = min(config.maxBackoff, (config.initialBackoff * (1 << failures).toDouble))
    val newInterval = min(config.maxInterval, (interval + config.intervalIncrement))
    val now = currentDateTime
    copy(nextScrape = now.plusSeconds(hoursToSeconds(backoff)),
         interval = newInterval,
         failures = this.failures + 1)
  }

  def withDocumentUnchanged()(implicit config: ScraperConfig) = {
    val newInterval = min(config.maxInterval, (interval + config.intervalIncrement))
    val now = currentDateTime
    copy(lastScrape = now,
         nextScrape = now.plusSeconds(hoursToSeconds(newInterval)),
         interval = newInterval,
         failures = 0)
  }

  def withDocumentChanged(newSignature: String)(implicit config: ScraperConfig) = {
    val newInterval = max(config.minInterval, interval - config.intervalDecrement)
    val now = currentDateTime
    copy(lastScrape = now,
         nextScrape = now.plusSeconds(hoursToSeconds(newInterval)),
         interval = newInterval,
         failures = 0,
         signature = newSignature)
  }

  private[this] def hoursToSeconds(hours: Double) = (hours * 60.0d * 60.0d).toInt

  def withNextScrape(nextScrape: DateTime) = copy(nextScrape = nextScrape)

  def save(implicit conn: Connection): ScrapeInfo = {
    val entity = ScrapeInfoEntity(this)
    assert(1 == entity.save())
    entity.view
  }
}

object ScrapeInfo {

  def all(implicit conn: Connection): Seq[ScrapeInfo] =
    ScrapeInfoEntity.all.map(_.view)

  def ofUri(uri: NormalizedURI)(implicit conn: Connection) = ofUriId(uri.id.get)

  def ofUriId(uriId: Id[NormalizedURI])(implicit conn: Connection) = {
    (ScrapeInfoEntity AS "s").map{ s => SELECT (s.*) FROM s WHERE (s.uriId EQ uriId) LIMIT 1 }.unique.map( _.view ) match {
      case Some(info) => info
      case None => ScrapeInfo(uriId = uriId).save
    }
  }

  def getOverdueList(limit: Int = -1, due: DateTime = currentDateTime)(implicit conn: Connection): Seq[ScrapeInfo] = {
    if (limit <= 0) {
      (ScrapeInfoEntity AS "s").map{ s => SELECT (s.*) FROM s WHERE ((s.nextScrape LE due) AND (s.state EQ ScrapeInfoStates.ACTIVE)) }.list.map( _.view )
    } else {
      (ScrapeInfoEntity AS "s").map{ s => SELECT (s.*) FROM s WHERE ((s.nextScrape LE due) AND (s.state EQ ScrapeInfoStates.ACTIVE)) LIMIT limit }.list.map( _.view )
    }
  }

  def get(id: Id[ScrapeInfo])(implicit conn: Connection): ScrapeInfo = getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[ScrapeInfo])(implicit conn: Connection): Option[ScrapeInfo] = ScrapeInfoEntity.get(id).map(_.view)

}

object ScrapeInfoStates {
  val ACTIVE = State[ScrapeInfo]("active")
  val INACTIVE = State[ScrapeInfo]("inactive")
}

private[model] class ScrapeInfoEntity extends Entity[ScrapeInfo, ScrapeInfoEntity] {
  val uriId = "uri_id".ID[NormalizedURI].NOT_NULL
  val lastScrape = "last_scrape".JODA_TIMESTAMP.NOT_NULL
  val nextScrape = "next_scrape".JODA_TIMESTAMP.NOT_NULL
  val interval = "scrape_interval".DOUBLE().NOT_NULL
  val failures = "failures".INTEGER.NOT_NULL
  val state = "state".STATE[ScrapeInfo].NOT_NULL
  val signature = "signature".VARCHAR(2046).NOT_NULL
  val destinationUrl = "destination_url".VARCHAR(2048)

  def relation = ScrapeInfoEntity

  def view(implicit conn: Connection): ScrapeInfo = ScrapeInfo(
    id = id.value,
    uriId = uriId(),
    lastScrape = lastScrape(),
    nextScrape = nextScrape(),
    interval = interval(),
    failures = failures(),
    state = state(),
    signature = signature(),
    destinationUrl = destinationUrl.value
  )
}

private[model] object ScrapeInfoEntity extends ScrapeInfoEntity with EntityTable[ScrapeInfo, ScrapeInfoEntity] {
  override def relationName = "scrape_info"

  def apply(view: ScrapeInfo): ScrapeInfoEntity = {
    val entity = new ScrapeInfoEntity
    entity.id.set(view.id)
    entity.uriId := view.uriId
    entity.lastScrape := view.lastScrape
    entity.nextScrape := view.nextScrape
    entity.interval := view.interval
    entity.failures := view.failures
    entity.state := view.state
    entity.signature := view.signature
    entity.destinationUrl.set(view.destinationUrl)
    entity
  }
}
