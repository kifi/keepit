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
  val BaselineDate = new LocalDate(2015, 1, 28)
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

  private def keepsInRepo(library: Library)(implicit session: RSession): Int = {
    keepRepo.getCountByLibrary(library.id.get)
  }

  private def path(lib: Library, owner: BasicUser): String = {
    s"${fortyTwoConfig.applicationBaseUrl}${Library.formatLibraryPath(owner.username, lib.slug)}"
  }

  private def lastMod(lib: Library): LocalDate = {
    val date = db.readOnlyReplica { implicit s =>
      keepRepo.latestKeepInLibrary(lib.id.get) match {
        case Some(date) if date.isAfter(lib.updatedAt) => date.toLocalDate
        case _ => lib.updatedAt.toLocalDate
      }
    }
    if (date.isBefore(LibrarySiteMapGenerator.BaselineDate)) {
      LibrarySiteMapGenerator.BaselineDate
    } else date
  }

  def generateAndCache(): Future[String] = generate() map { sitemap =>
    siteMapCache.set(SiteMapKey.libraries, sitemap)
    sitemap
  }

  def generate(): Future[String] = {
    db.readOnlyReplicaAsync { implicit ro =>
      val libs = libraryRepo.getAllPublishedLibraries().take(50000)
      if (libs.size > 40000) airbrake.notify(s"there are ${libs.size} libraries for sitemap, need to paginate the list!")
      libs
    } map { libraries =>

      // batch, optimize
      val ownerIds = libraries.map(_.ownerId).toSet
      val owners = db.readOnlyMaster { implicit ro =>
        val realUsers = ownerIds.filterNot(fakeUsers.contains)
        userRepo.loadAll(realUsers.toSet)
      } // cached
      val libs = db.readOnlyReplica { implicit ro =>
        libraries.filter { lib =>
          // proxy for quality; need bulk version. Library itself has a no-index with stricter rules
          owners.get(lib.ownerId).isDefined && keepsInRepo(lib) > 1
        }
      }

      val urlset =
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          {
            libs.map { lib =>
              <url>
                <loc>
                  { path(lib, owners(lib.ownerId)) }
                </loc>
                <lastmod>{ ISO_8601_DAY_FORMAT.print(lastMod(lib)) }</lastmod>
              </url>
            }
          }
        </urlset>
      log.info(s"[generate] done with sitemap generation. #libraries=${libs.size}")
      s"""
         |<?xml-stylesheet type='text/xsl' href='sitemap.xsl'?>
         |${urlset.toString}
       """.stripMargin.trim
    }
  }

}

