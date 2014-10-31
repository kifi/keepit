package com.keepit.common.seo

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.CollectionHelpers
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.model.{ UserRepo, Library, LibraryRepo }
import play.api.{ Play, Plugin }
import Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.xml.Elem

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

@Singleton
class SiteMapGenerator @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryCommander: LibraryCommander) extends Logging {

  // prototype/silver-bullet implementation -- will learn and improve
  def generate(): Future[Elem] = Future.successful {
    val libraries = db.readOnlyReplica { implicit ro => libraryRepo.getAllPublishedLibraries().take(50000) } // ahem does not scale
    // may want to add warning when > 40K

    // batch, optimize
    val ownerIds = CollectionHelpers.dedupBy(libraries.map(_.ownerId))(id => id)
    val owners = db.readOnlyMaster { implicit ro => userRepo.getUsers(ownerIds) } // cached
    val paths = libraries.filter { lib => owners.get(lib.ownerId).isDefined } map { lib =>
      val owner = owners(lib.ownerId)
      s"https://kifi.com${Library.formatLibraryPath(owner.username, owner.externalId, lib.slug)}"
    }

    // location only for now
    val urlset =
      <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
        {
          paths.map { path =>
            <url>
              <loc>
                { path }
              </loc>
            </url>
          }
        }
      </urlset>
    log.info(s"[generate] done with sitemap generation. #libraries=${paths.size}")
    urlset
  }

}

