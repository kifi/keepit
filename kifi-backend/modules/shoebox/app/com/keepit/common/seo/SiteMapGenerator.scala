package com.keepit.common.seo

import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RSession
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
import org.joda.time.{ LocalDate, DateTime }
import play.api.{ Play, Plugin }
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait SiteMapGeneratorPlugin extends Plugin {
  def submit()
}

class SiteMapGeneratorPluginImpl @Inject() (
    actor: ActorInstance[SiteMapGeneratorActor],
    val scheduling: SchedulingProperties) extends Logging with SiteMapGeneratorPlugin with SchedulerPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    for (app <- Play.maybeApplication) {
      val (initDelay, freq) = (60 minutes, 60 minutes)
      log.info(s"[onStart] SiteMapGeneratorPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTaskOnLeader(actor.system, initDelay, freq, actor.ref, SubmitSitemap)
    }
  }

  override def submit() { actor.ref ! SubmitSitemap }
}

object SubmitSitemap

// library-only for now
class SiteMapGeneratorActor @Inject() (
    airbrake: AirbrakeNotifier,
    httpClient: HttpClient,
    fortyTwoConfig: FortyTwoConfig) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case SubmitSitemap =>
      val sitemapUrl = java.net.URLEncoder.encode(s"${fortyTwoConfig.applicationBaseUrl}assets/sitemap.xml", "UTF-8")
      val googleRes = httpClient.get(DirectUrl(s"http://www.google.com/webmasters/sitemaps/ping?sitemap=$sitemapUrl"))
      log.info(s"submitted sitemap to google. res(${googleRes.status}): ${googleRes.body}")
      val bingRes = httpClient.get(DirectUrl(s"http://www.bing.com/webmaster/ping.aspx?siteMap=$sitemapUrl"))
      log.info(s"submitted sitemap to bing. res(${bingRes.status}): ${bingRes.body}")
  }

}

class SiteMapCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[SiteMapKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SiteMapKey(modelType: String) extends Key[String] {
  override val version = 1
  val namespace = "sitemap"
  def toKey(): String = modelType
}

object SiteMapKey {
  val libraries = SiteMapKey("libraries")
  val users = SiteMapKey("users")
}

object SiteMapGenerator {
  val BaselineDate = new LocalDate(2014, 12, 13)
}

@Singleton
class SiteMapGenerator @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    fortyTwoConfig: FortyTwoConfig,
    libraryRepo: LibraryRepo,
    experimentRepo: UserExperimentRepo,
    siteMapCache: SiteMapCache) extends Logging {

  def intern(): Future[String] = {
    siteMapCache.getOrElseFuture(SiteMapKey.libraries) {
      generate()
    }
  }

  private def keepsInRepo(library: Library)(implicit session: RSession): Int = {
    keepRepo.getCountByLibrary(library.id.get)
  }

  // prototype/silver-bullet implementation -- will learn and improve
  def generate(): Future[String] = {
    db.readOnlyReplicaAsync { implicit ro =>
      // ahem does not scale
      // may want to add warning when > 40K
      val libs = libraryRepo.getAllPublishedLibraries().take(50000)
      if (libs.size > 40000) airbrake.notify(s"there are ${libs.size} libraries for sitemap, need to paginate the list!")
      libs
    } map { libraries =>

      // batch, optimize
      val ownerIds = CollectionHelpers.dedupBy(libraries.map(_.ownerId))(id => id)
      val owners = db.readOnlyMaster { implicit ro =>
        val realUsers = ownerIds.filterNot { id =>
          val experiments = experimentRepo.getAllUserExperiments(id)
          experiments.contains(ExperimentType.FAKE) || experiments.contains(ExperimentType.AUTO_GEN)
        }
        userRepo.getUsers(realUsers)
      } // cached
      val libs = db.readOnlyReplica { implicit ro =>
        libraries.filter { lib =>
          owners.get(lib.ownerId).isDefined && keepsInRepo(lib) > 3 // proxy for quality; need bulk version
        }
      }

      def path(lib: Library): String = {
        val owner = owners(lib.ownerId)
        s"${fortyTwoConfig.applicationBaseUrl}${Library.formatLibraryPath(owner.username, lib.slug)}"
      }

      def lastMod(lib: Library): LocalDate = {
        val date = db.readOnlyReplica { implicit s =>
          keepRepo.latestKeepInLibrary(lib.id.get) match {
            case Some(date) if date.isAfter(lib.updatedAt) => date.toLocalDate
            case _ => lib.updatedAt.toLocalDate
          }
        }
        if (date.isBefore(SiteMapGenerator.BaselineDate)) {
          SiteMapGenerator.BaselineDate
        } else date
      }

      val urlset =
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          {
            libs.map { lib =>
              <url>
                <loc>
                  { path(lib) }
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

