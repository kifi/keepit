package com.keepit.common.seo

import com.keepit.commanders.UserCommander
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
  override def onStart() { //kill
    //    for (app <- Play.maybeApplication) {
    //      scheduleTaskOnOneMachine(actor.system, 3 hour, 12 hours, actor.ref, GenerateLibrarySitemap, GenerateLibrarySitemap.getClass.getSimpleName)
    //      scheduleTaskOnOneMachine(actor.system, 4 hour, 12 hours, actor.ref, GenerateUserSitemap, GenerateUserSitemap.getClass.getSimpleName)
    //    }
  }

  override def submit() { actor.ref ! GenerateLibrarySitemap }
}

trait SitemapGenerator {
  def generate(): Future[String]
  def generateAndCache(): Future[String]
  def intern(): Future[String]
  protected val experimentRepo: UserExperimentRepo
  protected val userCommander: UserCommander
  protected val db: Database
  protected val fakeUsers = userCommander.getAllFakeUsers() ++ db.readOnlyReplica { implicit s => experimentRepo.getUserIdsByExperiment(UserExperimentType.AUTO_GEN) }
}

object GenerateUserSitemap
object GenerateLibrarySitemap

// library-only for now
class SiteMapGeneratorActor @Inject() (
    airbrake: AirbrakeNotifier,
    httpClient: HttpClient,
    librarySiteMapGenerator: LibrarySiteMapGenerator,
    userSiteMapGenerator: UserSiteMapGenerator,
    fortyTwoConfig: FortyTwoConfig) extends FortyTwoActor(airbrake) with Logging {

  private def submitSitemap(sitemapUrl: String): Unit = {
    val googleRes = httpClient.get(DirectUrl(s"http://www.google.com/webmasters/sitemaps/ping?sitemap=$sitemapUrl"))
    log.info(s"submitted sitemap to google. res(${googleRes.status}): ${googleRes.body}")
    val bingRes = httpClient.get(DirectUrl(s"http://www.bing.com/webmaster/ping.aspx?siteMap=$sitemapUrl"))
    log.info(s"submitted sitemap to bing. res(${bingRes.status}): ${bingRes.body}")
  }

  def receive() = {
    case GenerateLibrarySitemap =>
      librarySiteMapGenerator.generateAndCache().onComplete { sitemap =>
        val sitemapUrl = java.net.URLEncoder.encode(s"${fortyTwoConfig.applicationBaseUrl}assets/sitemap-libraries-0.xml", "UTF-8")
        submitSitemap(sitemapUrl)
      }
    case GenerateUserSitemap =>
      userSiteMapGenerator.generateAndCache().onComplete { sitemap =>
        val sitemapUrl = java.net.URLEncoder.encode(s"${fortyTwoConfig.applicationBaseUrl}assets/sitemap-users-0.xml", "UTF-8")
        log.info(s"when user profile will be alive we'll send sitemap with this url to the search engines: $sitemapUrl")
        submitSitemap(sitemapUrl)
      }
  }

}

class SiteMapCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[SiteMapKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SiteMapKey(modelType: String) extends Key[String] {
  override val version = 9
  val namespace = "sitemap"
  def toKey(): String = modelType
}

object SiteMapKey {
  val libraries = SiteMapKey("libraries")
  val users = SiteMapKey("users")
}
