package com.keepit.rover.manager

import javax.inject.{ Inject, Singleton }

import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.model.UrlHash
import com.keepit.rover.manager.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }
import com.keepit.rover.model.{ ArticleInfoRepoImpl, ArticleInfoRepo }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

trait RoverManagerPlugin

@Singleton
class RoverManagerPluginImpl @Inject() (
    ingestionActor: ActorInstance[RoverArticleInfoIngestionActor],
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    fetchingActor: ActorInstance[RoverArticleFetchingActor],
    imageSchedulingActor: ActorInstance[RoverArticleImageSchedulingActor],
    imageProcessingActor: ActorInstance[RoverArticleImageProcessingActor],
    implicit val executionContext: ExecutionContext,
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    val scheduling: SchedulingProperties) extends RoverManagerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart(): Unit = {
    scheduleTaskOnOneMachine(ingestionActor.system, 187 seconds, 1 minute, ingestionActor.ref, IfYouCouldJustGoAhead, "NormalizedURI Ingestion")
    scheduleTaskOnOneMachine(fetchSchedulingActor.system, 200 seconds, 1 minute, fetchSchedulingActor.ref, IfYouCouldJustGoAhead, "Fetch Scheduling")
    scheduleTaskOnAllMachines(fetchingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, fetchingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnOneMachine(imageSchedulingActor.system, 200 seconds, 1 minute, imageSchedulingActor.ref, IfYouCouldJustGoAhead, "ArticleImage Scheduling")
    scheduleTaskOnAllMachines(imageProcessingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, imageProcessingActor.ref, IfYouCouldJustGoAhead)

    SafeFuture {
      val pageSize = 1000
      var processed = 0
      var done = false
      while (!done) {
        db.readWrite { implicit session =>
          val infos = articleInfoRepo.page(processed / pageSize, pageSize)
          val backfilled = infos.count { info =>
            if (info.urlHash.hash.isEmpty) {
              articleInfoRepo.asInstanceOf[ArticleInfoRepoImpl].saveSilently(info.copy(urlHash = UrlHash.hashUrl(info.url)))
              true
            } else false
          }
          log.info(s"Backfilled $backfilled/${infos.length} RoverArticleInfos with UrlHash")
          processed += infos.length
          done = infos.isEmpty
        }
      }
    }
    super.onStart()
  }

  override def onStop(): Unit = {
    Seq(ingestionActor, fetchSchedulingActor, fetchingActor, imageSchedulingActor, imageProcessingActor).foreach(_.ref ! Close)
    super.onStop()
  }
}
