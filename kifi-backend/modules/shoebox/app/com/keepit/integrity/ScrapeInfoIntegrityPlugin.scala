package com.keepit.integrity

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.actor.ActorInstance
import com.keepit.scraper.ScrapeScheduler
import scala.concurrent.duration._
import com.keepit.common.zookeeper.{ LongCentralConfigKey, SequenceNumberCentralConfigKey, CentralConfig }
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.db.slick.DBSession.RWSession
import akka.pattern.{ ask, pipe }
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.core._

case object SyncScrapeInfo

class ScrapeInfoIntegrityActor @Inject() (
    scrapeInfoIntegrityChecker: ScrapeInfoIntegrityChecker,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  @volatile private[this] var running = false

  case object Done

  def receive = {
    case SyncScrapeInfo =>
      if (!running) {
        running = true
        scrapeInfoIntegrityChecker.checkIntegrity().onComplete { case _ => self ! Done }
      }
    case Done =>
      running = false
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait ScrapeInfoIntegrityPlugin extends SchedulerPlugin {
  def checkScrapeInfo(): Unit
}

class ScrapeInfoIntegrityPluginImpl @Inject() (
    actor: ActorInstance[UriIntegrityActor],
    db: Database,
    uriRepo: NormalizedURIRepo,
    centralConfig: CentralConfig,
    val scheduling: SchedulingProperties) extends ScrapeInfoIntegrityPlugin with Logging {
  override def enabled = true
  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 60 seconds, 6 seconds, actor.ref, SyncScrapeInfo, this.getClass.getSimpleName)
  }

  def checkScrapeInfo(): Unit = {
    actor.ref ! SyncScrapeInfo
  }
}

class ScrapeInfoIntegrityChecker @Inject() (
    val db: Database,
    val scrapeInfoRepo: ScrapeInfoRepo,
    val scraper: ScrapeScheduler,
    clock: Clock,
    val normUriRepo: NormalizedURIRepo,
    val centralConfig: CentralConfig,
    val airbrake: AirbrakeNotifier) extends Logging {

  case class NormalizedUriSequenceNumberKey() extends SequenceNumberCentralConfigKey[NormalizedURI] {
    val longKey = new LongCentralConfigKey {
      def key: String = "NormalizedURISeq"
      val namespace: String = "ScrapeInfoIntegrityChecker"
    }
  }

  val normalizedURISeqKey = NormalizedUriSequenceNumberKey()

  private def getSequenceNumber(key: NormalizedUriSequenceNumberKey): SequenceNumber[NormalizedURI] = {
    centralConfig(key) getOrElse (SequenceNumber[NormalizedURI](64935824L)) // starting from a safe known sequence number
  }

  def forceSeqNum(seq: SequenceNumber[NormalizedURI]): Unit = centralConfig.update(normalizedURISeqKey, seq)

  def checkIntegrity(): Future[Unit] = SafeFuture {
    var numProcessed = 0
    var numScrapeInfoCreated = 0
    var seq = getSequenceNumber(normalizedURISeqKey)
    var done = false

    log.info("start processing NormalizedURIs")
    while (!done) {
      val normalizedURIs = db.readOnlyReplica { implicit s => normUriRepo.getIndexable(seq, 20) }
      done = normalizedURIs.isEmpty

      def collector(uri: NormalizedURI, fixedScrapeInfo: Boolean): Unit = {
        if (fixedScrapeInfo) numScrapeInfoCreated += 1
        numProcessed += 1
        seq = uri.seq
      }

      db.readWriteSeq(normalizedURIs, collector) { (s, uri) => checkIntegrity(uri)(s) }

      if (!done) centralConfig.update(normalizedURISeqKey, seq) // update high watermark
    }
    log.info(s"done: ${numProcessed} NormalizedURIs processed. Created ${numScrapeInfoCreated} ScrapeInfos")
  }

  private def checkIntegrity(uri: NormalizedURI)(implicit session: RWSession): Boolean = {
    uri.state match {
      case e: State[NormalizedURI] if DO_NOT_SCRAPE.contains(e) => // ensure no ACTIVE scrapeInfo records
        scrapeInfoRepo.getByUriId(uri.id.get) match {
          case Some(scrapeInfo) if scrapeInfo.state != ScrapeInfoStates.INACTIVE =>
            val savedSI = scrapeInfoRepo.save(scrapeInfo.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
            log.info(s"[save(${uri.toShortString})] mark scrapeInfo as INACTIVE; si=$savedSI")
            true
          case _ => false // do nothing
        }
      case SCRAPE_FAILED | SCRAPED =>
        scrapeInfoRepo.getByUriId(uri.id.get) match { // do NOT use saveStateAndNextScrape
          case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.INACTIVE =>
            val savedSI = scrapeInfoRepo.save(scrapeInfo.withState(ScrapeInfoStates.ACTIVE))
            log.info(s"[save(${uri.toShortString})] mark scrapeInfo as ACTIVE; si=$savedSI")
            true
          case _ => false // do nothing
        }
      case ACTIVE => false // do nothing
      case _ =>
        throw new IllegalStateException(s"Unhandled state=${uri.state}; uri=$uri")
    }
  }
}
