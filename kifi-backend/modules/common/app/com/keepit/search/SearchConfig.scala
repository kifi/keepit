package com.keepit.search

import java.io.File
import com.keepit.common.db.Id
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.{ ExperimentType, User }
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging
import com.keepit.common.usersegment.UserSegment
import play.api.libs.json._
import play.api.libs.json.JsObject

object SearchConfig {
  private[search] val defaultParams =
    Map[String, String](
      // Common Query Parser
      "titleBoost" -> "2.0",
      "prefixBoost" -> "1.0",
      "phraseBoost" -> "0.33",
      "siteBoost" -> "1.0",
      "concatBoost" -> "0.8",
      "homePageBoost" -> "0.2",

      // Shared Search Parameters (should probably not be shared)
      "percentMatch" -> "75",
      "halfDecayHours" -> "24",
      "recencyBoost" -> "1.0",

      // UriSearch
      "libraryNameBoost" -> "0.5",
      "maxResultClickBoost" -> "20.0",
      "minMyBookmarks" -> "2",
      "myBookmarkBoost" -> "1.5",
      "usefulPageBoost" -> "1.1",
      "sharingBoostInNetwork" -> "0.5",
      "sharingBoostOutOfNetwork" -> "0.01",
      "newContentBoost" -> "1.0",
      "tailCutting" -> "0.3",

      // LibrarySearch
      "librarySourceBoost" -> "3.0",
      "libraryOwnerBoost" -> "0.5",
      "myLibraryBoost" -> "1.5",
      "minMyLibraries" -> "1",

      // UserSearch
      "userSourceBoost" -> "3.0",
      "myFriendBoost" -> "1.5",

      "proximityBoost" -> "0.95",
      "dampingHalfDecayMine" -> "6.0",
      "dampingHalfDecayFriends" -> "4.0",
      "dampingHalfDecayOthers" -> "1.5",
      "forbidEmptyFriendlyHits" -> "true",
      "proximityGapPenalty" -> "0.05",
      "proximityPowerFactor" -> "1.0",
      "messageHalfLifeHours" -> "24"
    )
  private[this] val descriptions =
    Map[String, String](
      // Common Query Parser
      "titleBoost" -> "boost value for title field vs main content",
      "prefixBoost" -> "importance of prefix query vs regular text query",
      "phraseBoost" -> "boost value for the detected phrase [0f,1f]",
      "siteBoost" -> "boost value for matching website names and domains",
      "concatBoost" -> "boost value for concatenated terms",
      "homePageBoost" -> "boost value for home page [0f,1f]",

      // Shared Search Parameters (should probably not be shared)
      "percentMatch" -> "the minimum percentage of search terms have to match (weighted by IDF) for a result to show up",
      "halfDecayHours" -> "the time the recency boost becomes half",
      "recencyBoost" -> "importance of the recent bookmarks",

      // UriSearch
      "libraryNameBoost" -> "boost value for library name in uri search",
      "maxResultClickBoost" -> "boosting by recent result clicks",
      "minMyBookmarks" -> "the minimum number of my bookmarks in a search result",
      "myBookmarkBoost" -> "importance of my bookmark",
      "usefulPageBoost" -> "importance of usefulPage (clicked page)",
      "sharingBoostInNetwork" -> "importance of the number of friends sharing the bookmark",
      "sharingBoostOutOfNetwork" -> "importance of the number of others sharing the bookmark",
      "newContentBoost" -> "importance of a new content introduced to the network",
      "tailCutting" -> "after damping, a hit with a score below the high score multiplied by this will be removed",

      // LibrarySearch
      "librarySourceBoost" -> "boost value for library source in library search",
      "libraryOwnerBoost" -> "boost value for library owner in user search and a user's libraries in library search",
      "myLibraryBoost" -> "boost value for my own libraries in library search",
      "minMyLibraries" -> "the minimum number of my libraries in a library search result",

      // UserSearch
      "userSourceBoost" -> "boost value for user source in user search",
      "myFriendBoost" -> "boost value for my friends in user search",

      "proximityBoost" -> "boosting by proximity",
      "dampingHalfDecayMine" -> "how many top hits in my bookmarks are important",
      "dampingHalfDecayFriends" -> "how many top hits in friends' bookmarks are important",
      "dampingHalfDecayOthers" -> "how many top hits in others' bookmark are important",
      "forbidEmptyFriendlyHits" -> "when hits do not contain bookmarks from me or my friends, collapse results in the initial search",
      "proximityGapPenalty" -> "unit gap penalty, used in proximity query",
      "proximityPowerFactor" -> "raise proximity score to a power. Usually used in content field to penalize more on loose matches",
      "messageHalfLifeHours" -> "exponential time decay constant used in message search"
    )

  val empty = new SearchConfig(Map.empty)
  val defaultConfig = new SearchConfig(SearchConfig.defaultParams)

  def apply(params: (String, String)*): SearchConfig = SearchConfig(Map(params: _*))
  def getDescription(name: String) = descriptions.get(name)

  private[this] val segmentConfigs: mutable.HashMap[UserSegment, SearchConfig] = {
    val map = new mutable.HashMap[UserSegment, SearchConfig]() {
      override def default(key: UserSegment): SearchConfig = SearchConfig.defaultConfig
    }
    map += (UserSegment(3) -> SearchConfig.defaultConfig.overrideWith("dampingHalfDecayFriends" -> "2.5", "percentMatch" -> "85"))
  }
  def byUserSegment(seg: UserSegment): SearchConfig = segmentConfigs(seg)

  implicit val format = new Format[SearchConfig] {
    def reads(json: JsValue) = json.validate[JsObject].map { obj => SearchConfig(obj.fields.map { case (key, value) => (key, value.as[String]) }: _*) }
    def writes(config: SearchConfig) = JsObject(config.params.mapValues(JsString(_)).toSeq)
  }
}

class SearchConfigManager(configDir: Option[File], shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait) extends Logging {

  private[this] val consolidateGetExperimentsReq = new RequestConsolidator[String, Unit](ttl = 30 seconds)
  @volatile private[this] var _activeExperiments: Map[ExperimentType, SearchConfig] = Map()
  @volatile private[this] var _activeExperimentsExpiration: Long = 0L

  val defaultConfig = SearchConfig.defaultConfig

  def activeExperiments: Map[ExperimentType, SearchConfig] = {
    if (_activeExperimentsExpiration < System.currentTimeMillis) {
      consolidateGetExperimentsReq("active") { k => SafeFuture { syncActiveExperiments } }
    }
    _activeExperiments
  }

  def syncActiveExperiments: Unit = {
    try {
      _activeExperiments = monitoredAwait.result(shoeboxClient.getActiveExperiments, 5 seconds, "getting experiments").map(exp => exp.experiment -> exp.config).toMap
      _activeExperimentsExpiration = System.currentTimeMillis + (1000 * 60 * 5) // ttl 5 minutes
    } catch {
      case t: Throwable => _activeExperimentsExpiration = System.currentTimeMillis + (1000 * 30) // backoff 30 seconds
    }
  }

  private[this] var userConfig = Map.empty[Id[User], SearchConfig]
  def getUserConfig(userId: Id[User]) = userConfig.getOrElse(userId, defaultConfig)
  def setUserConfig(userId: Id[User], config: SearchConfig) { userConfig = userConfig + (userId -> config) }
  def resetUserConfig(userId: Id[User]) { userConfig = userConfig - userId }

  def getConfig(userId: Id[User], userExperiments: Set[ExperimentType]): (SearchConfig, Option[Id[SearchConfigExperiment]]) = {
    val future = getConfigFuture(userId, userExperiments)
    monitoredAwait.result(future, 1 seconds, "getting search config")
  }

  def getConfigFuture(userId: Id[User], userExperiments: Set[ExperimentType]): Future[(SearchConfig, Option[Id[SearchConfigExperiment]])] = {
    val segFuture = shoeboxClient.getUserSegment(userId)
    userConfig.get(userId) match {
      case Some(config) => Future.successful((config, None))
      case None =>
        val (experimentConfig, experimentId) =
          if (userExperiments.contains(ExperimentType.NO_SEARCH_EXPERIMENTS)) (SearchConfig.empty, None)
          else userExperiments.collectFirst {
            case experiment @ ExperimentType(SearchConfigExperiment.experimentTypePattern(id)) if activeExperiments.contains(experiment) =>
              (activeExperiments(experiment), Some(Id[SearchConfigExperiment](id.toLong)))
          } getOrElse (SearchConfig.empty, None)

        segFuture.map { seg =>
          val segmentConfig = SearchConfig.byUserSegment(seg)
          (segmentConfig.overrideWith(experimentConfig), experimentId)
        }
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

  def overrideWith(newParams: Map[String, String]): SearchConfig = {
    if (newParams.isEmpty) this else new SearchConfig(params ++ newParams)
  }
  def overrideWith(newParams: (String, String)*): SearchConfig = overrideWith(Map(newParams: _*))
  def overrideWith(config: SearchConfig): SearchConfig = overrideWith(config.params)

  def iterator: Iterator[(String, String)] = params.iterator
}
