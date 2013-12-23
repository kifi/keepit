package com.keepit.search

import java.io.File
import com.keepit.common.db.Id
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.User
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import com.keepit.search.query.StringHash64
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging
import com.keepit.common.usersegment.UserSegment

object SearchConfig {
  private[search] val defaultParams =
    Map[String, String](
      "phraseBoost" -> "0.33",
      "siteBoost" -> "1.0",
      "concatBoost" -> "0.8",
      "homePageBoost" -> "0.1",
      "similarity" -> "default",
      "svWeightMyBookMarks" -> "2",
      "svWeightClickHistory" -> "1",
      "maxResultClickBoost" -> "20.0",
      "minMyBookmarks" -> "2",
      "myBookmarkBoost" -> "1.5",
      "usefulPageBoost" -> "1.1",
      "sharingBoostInNetwork" -> "0.5",
      "sharingBoostOutOfNetwork" -> "0.01",
      "percentMatch" -> "75",
      "percentMatchForHotDocs" -> "60",
      "halfDecayHours" -> "24",
      "recencyBoost" -> "1.0",
      "newContentBoost" -> "1.0",
      "tailCutting" -> "0.3",
      "proximityBoost" -> "0.95",
      "semanticBoost" -> "0.8",
      "dampingHalfDecayMine" -> "5.0",
      "dampingHalfDecayFriends" -> "4.0",
      "dampingHalfDecayOthers" -> "1.5",
      "useS3FlowerFilter" -> "true",
      "showExperts" -> "false",
      "forbidEmptyFriendlyHits" -> "true",
      "useNonPersonalizedContextVector" -> "false",
      "useSemanticMatch" -> "false"
    )
  private[this] val descriptions =
    Map[String, String](
      "phraseBoost" -> "boost value for the detected phrase [0f,1f]",
      "siteBoost" -> "boost value for matching website names and domains",
      "concatBoost" -> "boost value for concatenated terms",
      "homePageBoost" -> "boost value for home page [0f,1f]",
      "similarity" -> "similarity characteristics",
      "svWeightMyBookMarks" -> "semantics vector weight for my bookmarks",
      "svWeightClickHistory" -> "semantic vector weight for click history",
      "maxResultClickBoost" -> "boosting by recent result clicks",
      "minMyBookmarks" -> "the minimum number of my bookmarks in a search result",
      "myBookmarkBoost" -> "importance of my bookmark",
      "usefulPageBoost" -> "importance of usefulPage",
      "sharingBoostInNetwork" -> "importance of the number of friends sharing the bookmark",
      "sharingBoostOutOfNetwork" -> "importance of the number of others sharing the bookmark",
      "percentMatch" -> "the minimum percentage of search terms have to match (weighted by IDF)",
      "percentMatchForHotDocs" -> "the minimum percentage of search terms have to match (weighted by IDF) for hot docs (useful pages)",
      "halfDecayHours" -> "the time the recency boost becomes half",
      "recencyBoost" -> "importance of the recent bookmarks",
      "newContentBoost" -> "importance of a new content introduced to the network",
      "tailCutting" -> "after damping, a hit with a score below the high score multiplied by this will be removed",
      "proximityBoost" -> "boosting by proximity",
      "semanticBoost" -> "boosting by semantic vector",
      "dampingHalfDecayMine" -> "how many top hits in my bookmarks are important",
      "dampingHalfDecayFriends" -> "how many top hits in friends' bookmarks are important",
      "dampingHalfDecayOthers" -> "how many top hits in others' bookmark are important",
      "useS3FlowerFilter" -> "Using the multiChunk S3 backed result clicked flower filter",
      "showExperts" -> "suggest experts when search returns hits",
      "forbidEmptyFriendlyHits" -> "when hits do not contain bookmarks from me or my friends, collapse results in the initial search",
      "useNonPersonalizedContextVector" -> "may use non-personalized context semantic vector",
      "useSemanticMatch" -> "use semantic boolean query"
    )

  val defaultConfig = new SearchConfig(SearchConfig.defaultParams)

  def apply(params: (String, String)*): SearchConfig = SearchConfig(Map(params:_*))
  def getDescription(name: String) = descriptions.get(name)
}

class SearchConfigManager(configDir: Option[File], shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait) extends Logging {

  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  private[this] val consolidateGetExperimentsReq = new RequestConsolidator[String, Unit](ttl = 30 seconds)
  @volatile private[this] var _activeExperiments: Seq[SearchConfigExperiment] = Seq()
  @volatile private[this] var _activeExperimentsExpiration: Long = 0L

  val defaultConfig = SearchConfig.defaultConfig

  def activeExperiments: Seq[SearchConfigExperiment] = {
    if (_activeExperimentsExpiration < System.currentTimeMillis) {
      consolidateGetExperimentsReq("active"){ k => SafeFuture { syncActiveExperiments } }
    }
    _activeExperiments
  }

  def userSegmentExperiments = activeExperiments.filter(_.description.contains("user segment experiment"))

  def syncActiveExperiments: Unit = {
    try {
      _activeExperiments = monitoredAwait.result(shoeboxClient.getActiveExperiments, 5 seconds, "getting experiments")
      _activeExperimentsExpiration = System.currentTimeMillis + (1000 * 60 * 5) // ttl 5 minutes
    } catch {
      case t: Throwable => _activeExperimentsExpiration = System.currentTimeMillis + (1000 * 30) // backoff 30 seconds
    }
  }

  private var userConfig = Map.empty[Long, SearchConfig]
  def getUserConfig(userId: Id[User]) = userConfig.getOrElse(userId.id, defaultConfig)
  def setUserConfig(userId: Id[User], config: SearchConfig) { userConfig = userConfig + (userId.id -> config) }
  def resetUserConfig(userId: Id[User]) { userConfig = userConfig - userId.id }

  // hash a user and a query to a number between 0 and 1
  private def hash(userId: Id[User], queryText: String): Double = {
    val hash = QueryHash(userId, queryText, analyzer)
    val (max, min) = (Long.MaxValue.toDouble, Long.MinValue.toDouble)
    (hash - min) / (max - min)
  }


  def getConfig(userId: Id[User], queryText: String, excludeFromExperiments: Boolean = false): (SearchConfig, Option[Id[SearchConfigExperiment]]) = {
    val segFuture = shoeboxClient.getUserSegment(userId)
    userConfig.get(userId.id) match {
      case Some(config) => (config, None)
      case None =>
        val experiment = if (excludeFromExperiments) None else {
          val hashFrac = hash(userId, queryText)
          var frac = 0.0
          activeExperiments.find { e =>
            frac += e.weight
            frac >= hashFrac
          }
        }

        val seg = monitoredAwait.result(segFuture, 1 seconds, "getting user segment")
        val configWithoutExperiment = SearchConfigOverrider.byUserSegment(seg, defaultConfig)
        (configWithoutExperiment(experiment.map(_.config.params).getOrElse(Map())), experiment.map(_.id.get))
      }
  }
}

case class SearchConfig(params: Map[String, String]) {
  def asInt(name: String) = params(name).toInt
  def asLong(name: String) = params(name).toLong
  def asFloat(name: String) = params(name).toFloat
  def asDouble(name: String) = params(name).toDouble
  def asBoolean(name: String) = params(name).toBoolean
  def asString(name: String) = params(name)

  def apply(newParams: Map[String, String]): SearchConfig = new SearchConfig(params ++ newParams)
  def apply(newParams: (String, String)*): SearchConfig = apply(Map(newParams:_*))

  def iterator: Iterator[(String, String)] = params.iterator
}

/**
 * lightweight config overwriters. These configs are not recorded in DB. They are in action unless overwritten by search experiments.
 */
object SearchConfigOverrider extends Logging{
  def byUserSegment(seg: UserSegment, config: SearchConfig): SearchConfig = {
    seg.value match {
      case 3 => new SearchConfig(config.params ++ Map("dampingHalfDecayFriends" -> "2.5", "percentMatch" -> "85"))
      case _ => config
    }
  }
}
