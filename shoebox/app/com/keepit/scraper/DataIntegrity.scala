package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
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
import play.api.Plugin
import akka.actor.{Actor, Cancellable, Props, ActorSystem}
import com.keepit.model.NormalizedURI
import com.keepit.model.ScrapeInfo
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor

trait DataIntegrityPlugin extends Plugin {
  def cron(): Unit
}

class DataIntegrityPluginImpl @Inject() (system: ActorSystem)
  extends Logging with DataIntegrityPlugin {

  private val actor = system.actorOf(Props { new DataIntegrityActor() })
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(10 seconds, 1 hour, actor, CleanOrphans)
    )
  }

  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }

  override def cron(): Unit = {
    if (currentDateTime.hourOfDay().get() == 21) // 9pm PST
      actor ! CleanOrphans
  }
}

private[scraper] case object CleanOrphans

private[scraper] class DataIntegrityActor() extends FortyTwoActor with Logging {

  def receive() = {
    case CleanOrphans =>
      val orphanCleaner = new OrphanCleaner
      inject[DBConnection].readWrite { implicit session =>
        orphanCleaner.cleanNormalizedURIs()
        orphanCleaner.cleanScrapeInfo()
      }
    case unknown =>
      throw new Exception("unknown message: %s".format(unknown))
  }
}

class OrphanCleaner {

  def cleanNormalizedURIs(readOnly: Boolean = true)(implicit session: RWSession) = {
    val nuriRepo = inject[NormalizedURIRepo]
    val nuris = nuriRepo.allActive()
    val urlRepo = inject[URLRepo]
    var changedNuris = Seq[NormalizedURI]()
    nuris map { nuri =>
      val urls = urlRepo.getByNormUri(nuri.id.get)
      if (urls.isEmpty)
        changedNuris = changedNuris.+:(nuri)
    }
    if (!readOnly) {
      changedNuris map { nuri =>
        nuriRepo.save(nuri.withState(NormalizedURIStates.INACTIVE))
      }
    }
    changedNuris.map(_.id.get)
  }

  def cleanScrapeInfo(readOnly: Boolean = true)(implicit session: RWSession) = {
    val scrapeInfoRepo = inject[ScrapeInfoRepo]
    val sis = scrapeInfoRepo.all() // allActive does the join with nuri. Come up with a better way?
    val nuriRepo = inject[NormalizedURIRepo]
    var oldScrapeInfos = Seq[ScrapeInfo]()
    sis map { si =>
      if (si.state == ScrapeInfoStates.ACTIVE) {
        val nuri = nuriRepo.get(si.uriId)
        if (nuri.state == NormalizedURIStates.INACTIVE)
          oldScrapeInfos = oldScrapeInfos.+:(si)
      }
    }
    if (!readOnly) {
      oldScrapeInfos map { si =>
        scrapeInfoRepo.save(si.withState(ScrapeInfoStates.INACTIVE))
      }
    }
    oldScrapeInfos.map(_.id.get)
  }
}

