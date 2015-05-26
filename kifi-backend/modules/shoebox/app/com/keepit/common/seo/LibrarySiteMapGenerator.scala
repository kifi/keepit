package com.keepit.common.seo

import com.keepit.commanders.UserCommander
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.{ seo, CollectionHelpers }
import com.keepit.common.cache._
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.time._
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.{ LocalDate, DateTime }
import play.api.{ Play, Plugin }
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object LibrarySiteMapGenerator {
  val BaselineDate = new LocalDate(2015, 3, 15)
}

@Singleton
class LibrarySiteMapGenerator @Inject() (
    airbrake: AirbrakeNotifier,
    siteMapCache: SiteMapCache,
    userRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    fortyTwoConfig: FortyTwoConfig,
    libraryRepo: LibraryRepo,
    protected val experimentRepo: UserExperimentRepo,
    protected val db: Database,
    protected val userCommander: UserCommander) extends SitemapGenerator with Logging {

  def intern(): Future[String] = {
    siteMapCache.getOrElseFuture(SiteMapKey.libraries) {
      generate()
    }
  }

  private def path(lib: Library, owner: BasicUser): String = {
    s"${fortyTwoConfig.applicationBaseUrl}${Library.formatLibraryPath(owner.username, lib.slug)}"
  }

  def generateAndCache(): Future[String] = generate() map { sitemap =>
    siteMapCache.set(SiteMapKey.libraries, sitemap)
    sitemap
  }

  private val MinKeepCount = 4

  def generate(): Future[String] = {
    db.readOnlyReplicaAsync { implicit ro =>
      val libIds = libraryRepo.getAllPublishedNonEmptyLibraries(MinKeepCount).take(50000)
      libIds
    } map { ids =>
      val libs = ids.map { id =>
        db.readOnlyMaster { implicit s =>
          val lib = libraryRepo.get(id)
          if (lib.lastKept.isDefined && lib.keepCount >= MinKeepCount && !fakeUsers.contains(lib.ownerId)) Some(lib -> userRepo.load(lib.ownerId))
          else None
        }
      }.flatten
      val libsSize = libs.size
      if (libsSize > 45000) airbrake.notify(s"there are $libsSize libraries for sitemap (MinKeepCount=$MinKeepCount), need to paginate the list!")
      val urlset =
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          {
            libs.map {
              case libInfo =>
                <url>
                  <loc>
                    { path(libInfo._1, libInfo._2) }
                  </loc>
                  <lastmod>{ ISO_8601_DAY_FORMAT.print(libInfo._1.lastKept.get) }</lastmod>
                </url>
            }
          }
        </urlset>
      log.info(s"[generate] done with sitemap generation. #libraries=$libsSize")
      s"""
         |<?xml-stylesheet type='text/xsl' href='sitemap.xsl'?>
         |${urlset.toString}
       """.stripMargin.trim
    }
  }

}

