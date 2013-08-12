package com.keepit.integrity

import com.keepit.common.logging.Logging
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model._
import org.apache.http.HttpStatus
import org.joda.time.{DateTime, Seconds}
import scala.collection.Seq
import akka.actor.{Actor, Cancellable, Props, ActorSystem}
import com.keepit.model.NormalizedURI
import com.keepit.model.ScrapeInfo
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}


trait DataIntegrityPlugin extends SchedulingPlugin

class DataIntegrityPluginImpl @Inject() (
    actor: ActorInstance[DataIntegrityActor],
    val schedulingProperties: SchedulingProperties) //only on leader
  extends Logging with DataIntegrityPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 5 minutes, 1 hour, actor.ref, Cron)
  }
}

private[integrity] case object CleanOrphans
private[integrity] case object Cron

private[integrity] class DataIntegrityActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    db: Database,
    orphanCleaner: OrphanCleaner)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case CleanOrphans =>
      db.readWrite { implicit session =>
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

class OrphanCleaner @Inject() (
    urlRepo: URLRepo,
    nuriRepo: NormalizedURIRepo,
    scrapeInfoRepo: ScrapeInfoRepo) extends Logging {

  def cleanNormalizedURIs(readOnly: Boolean = true)(implicit session: RWSession) = {
    val nuris = nuriRepo.allActive()
    var changedNuris = Seq[NormalizedURI]()
    nuris foreach { nuri =>
      val urls = urlRepo.getByNormUri(nuri.id.get)
      if (urls.isEmpty)
        changedNuris = changedNuris.+:(nuri)
    }
    if (!readOnly) {
      log.info(s"Changing ${changedNuris.size} NormalizedURIs.")
      changedNuris foreach { nuri =>
        nuriRepo.save(nuri.withState(NormalizedURIStates.INACTIVE))
      }
    } else {
      log.info(s"Would have changed ${changedNuris.size} NormalizedURIs.")
    }
    changedNuris.map(_.id.get)
  }

  def cleanScrapeInfo(readOnly: Boolean = true)(implicit session: RWSession) = {
    val sis = scrapeInfoRepo.all() // allActive does the join with nuri. Come up with a better way?
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

