package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.actor.ActorFactory
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model._
import com.google.inject.Inject
import org.apache.http.HttpStatus
import org.joda.time.{DateTime, Seconds}
import play.api.Play.current
import scala.collection.Seq
import akka.actor.{Actor, Cancellable, Props, ActorSystem}
import com.keepit.model.NormalizedURI
import com.keepit.model.ScrapeInfo
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.SchedulingPlugin

trait DataIntegrityPlugin extends SchedulingPlugin

class DataIntegrityPluginImpl @Inject() (
    actorFactory: ActorFactory[DataIntegrityActor])
  extends Logging with DataIntegrityPlugin {

  private val actor = actorFactory.get()
  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 5 minutes, 1 hour, actor, Cron)
  }
}

private[scraper] case object CleanOrphans
private[scraper] case object Cron

private[scraper] class DataIntegrityActor() extends FortyTwoActor with Logging {

  def receive() = {
    case CleanOrphans =>
      val orphanCleaner = new OrphanCleaner
      inject[Database].readWrite { implicit session =>
        // This cleans up cases when we have a normalizedUri, but no Url. This *only* happens when we renormalize, so does not need to happen every night.
        //orphanCleaner.cleanNormalizedURIs(false)
        orphanCleaner.cleanScrapeInfo(false)
      }
    case Cron =>
      if (currentDateTime.hourOfDay().get() == 21) // 9pm PST
        self ! CleanOrphans
    case unknown =>
      throw new Exception("unknown message: %s".format(unknown))
  }
}

class OrphanCleaner extends Logging {

  def cleanNormalizedURIs(readOnly: Boolean = true)(implicit session: RWSession) = {
    val nuriRepo = inject[NormalizedURIRepo]
    val nuris = nuriRepo.allActive()
    val urlRepo = inject[URLRepo]
    var changedNuris = Seq[NormalizedURI]()
    nuris foreach { nuri =>
      val urls = urlRepo.getByNormUri(nuri.id.get)
      if (urls.isEmpty)
        changedNuris = changedNuris.+:(nuri)
    }
    if (!readOnly) {
      log.info(s"Changing ${changedNuris.size} NormalizedURIs.")
      changedNuris foreach { nuri =>
        nuriRepo.save(nuri.withState(NormalizedURIStates.ACTIVE))
      }
    } else {
      log.info(s"Would have changed ${changedNuris.size} NormalizedURIs.")
    }
    changedNuris.map(_.id.get)
  }

  def cleanScrapeInfo(readOnly: Boolean = true)(implicit session: RWSession) = {
    val scrapeInfoRepo = inject[ScrapeInfoRepo]
    val sis = scrapeInfoRepo.all() // allActive does the join with nuri. Come up with a better way?
    val nuriRepo = inject[NormalizedURIRepo]
    var oldScrapeInfos = Seq[ScrapeInfo]()
    sis foreach { si =>
      if (si.state == ScrapeInfoStates.ACTIVE) {
        val nuri = nuriRepo.get(si.uriId)
        if (nuri.state == NormalizedURIStates.INACTIVE || nuri.state == NormalizedURIStates.ACTIVE)
          oldScrapeInfos = oldScrapeInfos.+:(si)
      }
    }
    if (!readOnly) {
      log.info(s"Changing ${oldScrapeInfos.size} ScrapeInfos.")
      oldScrapeInfos foreach { si =>
        scrapeInfoRepo.save(si.withState(ScrapeInfoStates.INACTIVE))
      }
    } else {
      log.info(s"Would have changed ${oldScrapeInfos.size} ScrapeInfos.")
    }
    oldScrapeInfos.map(_.id.get)
  }
}

