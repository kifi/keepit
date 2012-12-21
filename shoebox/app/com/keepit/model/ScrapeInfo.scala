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
  id: Option[Id[ScrapeInfo]], // = NomralizedURI id
  lastScrape: DateTime = currentDateTime,
  nextScrape: DateTime = currentDateTime,
  interval: Double = 24.0d, // hours
  failures: Int = 0,
  state: State[ScrapeInfo] = ScrapeInfo.States.ACTIVE,
  signature: String = ""
) {

  def withState(state: State[ScrapeInfo]) = copy(state = state)

  def withFailure()(implicit config: ScraperConfig) = {
    val retryDelay = min(config.maxRetryDelay, (1 << failures))
    val newInterval = min(config.maxInterval, (interval + config.intervalIncrement))
    val now = currentDateTime
    copy(nextScrape = now.plusSeconds(hoursToSeconds(retryDelay)),
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

  def save(implicit conn: Connection): ScrapeInfo = {
    val entity = ScrapeInfoEntity(this)
    assert(1 == entity.save())
    entity.view
  }
}

object ScrapeInfo {

  def forNormalizedURI(uri: NormalizedURI)(implicit conn: Connection) = {
    val id = uri.id.map(id => Id[ScrapeInfo](id.id))
    getOpt(id.get).getOrElse(ScrapeInfo(id))
  }

  def get(id: Id[ScrapeInfo])(implicit conn: Connection): ScrapeInfo = getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[ScrapeInfo])(implicit conn: Connection): Option[ScrapeInfo] = ScrapeInfoEntity.get(id).map(_.view)

  def getOverdueList(limit: Int = -1, due: DateTime = currentDateTime)(implicit conn: Connection): Seq[ScrapeInfo] = {
    if (limit <= 0) {
      (ScrapeInfoEntity AS "s").map{ s => SELECT (s.*) FROM s WHERE ((s.nextScrape GE due) AND (s.state EQ States.ACTIVE)) }.list.map( _.view )
    } else {
      (ScrapeInfoEntity AS "s").map{ s => SELECT (s.*) FROM s WHERE ((s.nextScrape GE due) AND (s.state EQ States.ACTIVE)) LIMIT limit }.list.map( _.view )
    }
  }

  object States {
    val ACTIVE = State[ScrapeInfo]("active")
    val INACTIVE = State[ScrapeInfo]("inactive")
  }
}

private[model] class ScrapeInfoEntity extends Entity[ScrapeInfo, ScrapeInfoEntity] {
  val lastScrape = "lastScrape".JODA_TIMESTAMP.NOT_NULL
  val nextScrape = "nextScrape".JODA_TIMESTAMP.NOT_NULL
  val interval = "interval".DOUBLE().NOT_NULL
  val failures = "failures".INTEGER.NOT_NULL
  val state = "state".STATE[ScrapeInfo].NOT_NULL
  val signature = "signature".VARCHAR(2046).NOT_NULL

  def relation = ScrapeInfoEntity

  def view(implicit conn: Connection): ScrapeInfo = ScrapeInfo(
    id = id.value,
    lastScrape = lastScrape(),
    nextScrape = nextScrape(),
    state = state()
  )
}

private[model] object ScrapeInfoEntity extends ScrapeInfoEntity with EntityTable[ScrapeInfo, ScrapeInfoEntity] {
  override def relationName = "scrape_info"

  def apply(view: ScrapeInfo): ScrapeInfoEntity = {
    val entity = new ScrapeInfoEntity
    entity.id.set(view.id)
    entity.lastScrape := view.lastScrape
    entity.nextScrape := view.nextScrape
    entity.interval := view.interval
    entity.failures := view.failures
    entity.state := view.state
    entity.signature := view.signature
    entity
  }
}
