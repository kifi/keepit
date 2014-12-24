package com.keepit.common.seo

import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.Paginator
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

object UserSiteMapGenerator {
  val BaselineDate = new LocalDate(2014, 12, 23)
}

@Singleton
class UserSiteMapGenerator @Inject() (airbrake: AirbrakeNotifier,
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    fortyTwoConfig: FortyTwoConfig,
    experimentRepo: UserExperimentRepo,
    siteMapCache: SiteMapCache) extends SitemapGenerator with Logging {

  def intern(): Future[String] = {
    siteMapCache.getOrElseFuture(SiteMapKey.users) {
      generate()
    }
  }

  private def lastMod(user: User): LocalDate = {
    val date = {
      val userUpdate = user.updatedAt.toLocalDate
      //seems like we're timing out on sitemap creation and this call is pretty slow
      //after we'll stop timing out i'll go back to check if we can make it happen
      //      val lastLib = db.readOnlyReplica { implicit s =>
      //        libraryRepo.getLibrariesOfUserFromAnonymos(user.id.get, Paginator.fromStart(1)).headOption.map(_.updatedAt.toLocalDate).getOrElse(userUpdate)
      //      }
      //      if (userUpdate.isAfter(lastLib)) userUpdate else lastLib
      userUpdate
    }
    if (date.isBefore(UserSiteMapGenerator.BaselineDate)) {
      UserSiteMapGenerator.BaselineDate
    } else date
  }

  def generateAndCache(): Future[String] = generate() map { sitemap =>
    siteMapCache.set(SiteMapKey.users, sitemap)
    sitemap
  }

  def generate(): Future[String] = {
    db.readOnlyReplicaAsync { implicit ro =>
      val users = userRepo.getAllIds()
      if (users.size > 40000) airbrake.notify(s"there are ${users.size} libraries for sitemap, need to paginate the list!")
      users
    } map { userIds =>
      val users: Iterator[User] = userIds.grouped(100) flatMap { group =>
        db.readOnlyMaster { implicit ro =>
          val realUsers = group.filterNot { id =>
            val experiments = experimentRepo.getAllUserExperiments(id)
            experiments.contains(ExperimentType.FAKE) || experiments.contains(ExperimentType.AUTO_GEN)
          }
          userRepo.getAllUsers(realUsers.toSeq).values.toSeq
        }
      }
      val urlset =
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          {
            users.map { user =>
              <url>
                <loc>
                  { s"https://www.kifi.com/${user.username}" }
                </loc>
                <lastmod>{ ISO_8601_DAY_FORMAT.print(lastMod(user)) }</lastmod>
              </url>
            }
          }
        </urlset>
      log.info(s"[generate] done with sitemap generation. #libraries=${users.size}")
      s"""
         |<?xml-stylesheet type='text/xsl' href='sitemap.xsl'?>
         |${urlset.toString}
       """.stripMargin.trim
    }
  }

}

