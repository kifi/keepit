package com.keepit.common.seo

import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.CollectionHelpers
import com.keepit.common.cache._
import com.keepit.common.time._
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ KeepRepo, UserRepo, Library, LibraryRepo }
import org.joda.time.DateTime
import play.api.{ Play, Plugin }
import Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait SiteMapGeneratorPlugin extends Plugin {
  def generate()
}

class SiteMapGeneratorPluginImpl @Inject() (
    actor: ActorInstance[SiteMapGeneratorActor],
    val scheduling: SchedulingProperties) extends Logging with SiteMapGeneratorPlugin with SchedulerPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    for (app <- Play.maybeApplication) {
      val (initDelay, freq) = if (Play.isDev) (30 seconds, 10 minutes) else (15 minutes, 60 minutes)
      log.info(s"[onStart] SiteMapGeneratorPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTaskOnLeader(actor.system, initDelay, freq, actor.ref, Generate)
    }
  }

  override def generate() { actor.ref ! Generate }

}

case class Generate()

// library-only for now
class SiteMapGeneratorActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    generator: SiteMapGenerator,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryCommander: LibraryCommander) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Generate =>
    // todo: generate and save to disk, maybe
  }

}

class SiteMapCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[SiteMapKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SiteMapKey() extends Key[String] {
  override val version = 1
  val namespace = "sitemap"
  def toKey(): String = ""
}

@Singleton
class SiteMapGenerator @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    fortyTwoConfig: FortyTwoConfig,
    libraryRepo: LibraryRepo,
    siteMapCache: SiteMapCache,
    libraryCommander: LibraryCommander) extends Logging {

  def intern(): Future[String] = {
    siteMapCache.getOrElseFuture(SiteMapKey()) {
      generate()
    }
  }

  // prototype/silver-bullet implementation -- will learn and improve
  def generate(): Future[String] = {
    db.readOnlyReplicaAsync { implicit ro =>
      // ahem does not scale
      // may want to add warning when > 40K
      libraryRepo.getAllPublishedLibraries().take(50000)
    } map { libraries =>

      // batch, optimize
      val ownerIds = CollectionHelpers.dedupBy(libraries.map(_.ownerId))(id => id)
      val owners = db.readOnlyMaster { implicit ro => userRepo.getUsers(ownerIds) } // cached
      val libs = libraries.filter { lib =>
        owners.get(lib.ownerId).isDefined && db.readOnlyMaster { implicit ro => keepRepo.getCountByLibrary(lib.id.get) > 3 } // proxy for quality; need bulk version
      }

      def path(lib: Library): String = {
        val owner = owners(lib.ownerId)
        s"${fortyTwoConfig.applicationBaseUrl}{Library.formatLibraryPath(owner.username, owner.externalId, lib.slug)}"
      }

      def lastMod(lib: Library): DateTime = {
        db.readOnlyReplica { implicit s =>
          keepRepo.latestKeepInLibrary(lib.id.get) match {
            case Some(date) if date.isAfter(lib.updatedAt) => date
            case _ => lib.updatedAt
          }
        }
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
       """.stripMargin
    }
  }

}

