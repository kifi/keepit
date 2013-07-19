package com.keepit.search

import java.io.File
import com.keepit.common.db.Id
import com.keepit.common.akka.MonitoredAwait
import com.keepit.model.User
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._

object SearchConfig {
  private[search] val defaultParams =
    Map[String, String](
      "decorateResultWithCollections" -> "true",
      "phraseProximityBoost" -> "0.95",     // could be too aggressive?
      "phraseBoost" -> "0.5",
      "siteBoost" -> "1.0",
      "similarity" -> "default",
      "svWeightMyBookMarks" -> "1",
      "svWeightBrowsingHistory" -> "2",
      "svWeightClickHistory" -> "3",
      "maxResultClickBoost" -> "10.0",
      "minMyBookmarks" -> "2",
      "myBookmarkBoost" -> "1.5",
      "sharingBoostInNetwork" -> "0.5",
      "sharingBoostOutOfNetwork" -> "0.1",
      "percentMatch" -> "75",
      "halfDecayHours" -> "24",
      "recencyBoost" -> "1.0",
      "newContentBoost" -> "1.0",
      "newContentDiscoveryThreshold" -> "0.5",
      "tailCutting" -> "0.25",
      "proximityBoost" -> "0.8",
      "semanticBoost" -> "0.8",
      "dampingHalfDecayMine" -> "7.0",
      "dampingHalfDecayFriends" -> "5.0",
      "dampingHalfDecayOthers" -> "2.0",
      "useS3FlowerFilter" -> "true",
      "showExperts" -> "false"
    )
  private[this] val descriptions =
    Map[String, String](
      "decorateResultWithCollections" -> "decorates result with collection information",
      "phraseProximityBoost" -> "boost value for phrase proximity",
      "phraseBoost" -> "boost value for the detected phrase",
      "siteBoost" -> "boost value for matching website names and domains",
      "similarity" -> "similarity characteristics",
      "svWeightMyBookMarks" -> "semantics vector weight for my bookmarks",
      "svWeightBrowsingHistory" -> "semantic vector weight for browsing history",
      "svWeightClickHistory" -> "semantic vector weight for click history",
      "maxResultClickBoost" -> "boosting by recent result clicks",
      "minMyBookmarks" -> "the minimum number of my bookmarks in a search result",
      "myBookmarkBoost" -> "importance of my bookmark",
      "sharingBoostInNetwork" -> "importance of the number of friends sharing the bookmark",
      "sharingBoostOutOfNetwork" -> "importance of the number of others sharing the bookmark",
      "percentMatch" -> "the minimum percentage of search terms have to match (weighted by IDF)",
      "halfDecayHours" -> "the time the recency boost becomes half",
      "recencyBoost" -> "importance of the recent bookmarks",
      "newContentBoost" -> "importance of a new content introduced to the network",
      "newContentDiscoveryThreshold" -> "minimum recency score to instance new content in a result",
      "tailCutting" -> "after damping, a hit with a score below the high score multiplied by this will be removed",
      "proximityBoost" -> "boosting by proximity",
      "semanticBoost" -> "boosting by semantic vector",
      "dampingHalfDecayMine" -> "how many top hits in my bookmarks are important",
      "dampingHalfDecayFriends" -> "how many top hits in friends' bookmarks are important",
      "dampingHalfDecayOthers" -> "how many top hits in others' bookmark are important",
      "useS3FlowerFilter" -> "Using the multiChunk S3 backed result clicked flower filter",
      "showExperts" -> "suggest experts when search returns hits"
    )

  def apply(params: (String, String)*): SearchConfig = SearchConfig(Map(params:_*))
  def getDescription(name: String) = descriptions.get(name)
}

class SearchConfigManager(configDir: Option[File], shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait) {

  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  @volatile private[this] var _activeExperiments: Seq[SearchConfigExperiment] = Seq()

  private val propertyFileName = "searchconfig.properties"

  lazy val defaultConfig = new SearchConfig(SearchConfig.defaultParams)

  def activeExperiments: Seq[SearchConfigExperiment] = {
    val ret = monitoredAwait.result(shoeboxClient.getActiveExperiments, 5 milliseconds, "getting experiments", _activeExperiments)
    _activeExperiments = ret
    ret
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

        (defaultConfig(experiment.map(_.config.params).getOrElse(Map())), experiment.map(_.id.get))
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
