package com.keepit.search

import java.io.File
import java.io.FileInputStream
import java.util.Properties

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.ExperimentTypes.NO_SEARCH_EXPERIMENTS
import com.keepit.model.{UserExperimentRepo, User}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash

object SearchConfig {
  private[search] val defaultParams =
    Map[String, String](
      "phraseBoost" -> "1.0",
      "siteBoost" -> "2.0",
      "enableCoordinator" -> "true",
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
      "tailCutting" -> "0.20",
      "proximityBoost" -> "2.0",
      "semanticBoost" -> "5.0",
      "dumpingByRank" -> "true",
      "dumpingHalfDecayMine" -> "8.0",
      "dumpingHalfDecayFriends" -> "6.0",
      "dumpingHalfDecayOthers" -> "2.0"
    )
  private[this] val descriptions =
    Map[String, String](
      "phraseBoost" -> "boost value for the detected phrase",
      "siteBoost" -> "boost value for matching website names and domains",
      "enableCoordinator" -> "enables the IDF based coordinator",
      "similarity" -> "similarity characteristics",
      "svWeightMyBookMarks" -> "semantics vector weight for my bookmarks",
      "svWeightBrowsingHistory" -> "semantic vector weight for browsing history",
      "svWeightBrowsingHistory" -> "semantic vector weight for click history",
      "maxResultClickBoost" -> "boosting by recent result clicks",
      "minMyBookmarks" -> "the minimum number of my bookmarks in a search result",
      "myBookmarkBoost" -> "importance of my bookmark",
      "personalTitleBoost" -> "boosting the personal bookmark title score when there is no match in the article",
      "sharingBoostInNetwork" -> "importance of the number of friends sharing the bookmark",
      "sharingBoostOutOfNetwork" -> "importance of the number of others sharing the bookmark",
      "percentMatch" -> "the minimum percentage of search terms have to match (weighted by IDF)",
      "halfDecayHours" -> "the time the recency boost becomes half",
      "recencyBoost" -> "importance of the recent bookmarks",
      "tailCutting" -> "after dumping, a hit with a score below the high score multiplied by this will be removed",
      "proximityBoost" -> "boosting by proximity",
      "semanticBoost" -> "boosting by semantic vector",
      "dumpingByRank" -> "enable score dumping by rank",
      "dumpingHalfDecayMine" -> "how many top hits in my bookmarks are important",
      "dumpingHalfDecayFriends" -> "how many top hits in friends' bookmarks are important",
      "dumpingHalfDecayOthers" -> "how many top hits in others' bookmark are important"
    )

  def apply(params: (String, String)*): SearchConfig = SearchConfig(Map(params:_*))
  def getDescription(name: String) = descriptions.get(name)
}

class SearchConfigManager(
    configDir: Option[File],
    experimentRepo: SearchConfigExperimentRepo,
    userExperimentRepo: UserExperimentRepo,
    db: Database) {

  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  private val propertyFileName = "searchconfig.properties"

  lazy val defaultConfig = {
    configDir.flatMap{ dir =>
      val file = new File(dir, propertyFileName)
      if (file.exists()) {
        val prop = new Properties()
        prop.load(new FileInputStream(file))
        val defaults = SearchConfig.defaultParams.foldLeft(Map.empty[String, String]){
          case (m, (k, v)) => m + (k -> Option(prop.getProperty(k)).getOrElse(v))
        }
        Some(new SearchConfig(defaults))
      } else {
        None
      }
    }.getOrElse(new SearchConfig(SearchConfig.defaultParams))
  }

  def activeExperiments: Seq[SearchConfigExperiment] =
    db.readOnly { implicit s => experimentRepo.getActive() }

  def getExperiments: Seq[SearchConfigExperiment] =
    db.readOnly { implicit s => experimentRepo.getNotInactive() }

  def getExperiment(id: Id[SearchConfigExperiment]): SearchConfigExperiment =
    db.readOnly { implicit s => experimentRepo.get(id) }

  def saveExperiment(experiment: SearchConfigExperiment): SearchConfigExperiment =
    db.readWrite { implicit s => experimentRepo.save(experiment) }

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

  def getConfig(userId: Id[User], queryText: String): (SearchConfig, Option[Id[SearchConfigExperiment]]) = {
    userConfig.get(userId.id) match {
      case Some(config) => (config, None)
      case None =>
        val shouldExclude = db.readOnly { implicit s =>
          userExperimentRepo.hasExperiment(userId, NO_SEARCH_EXPERIMENTS)
        }
        val experiment = if (shouldExclude) None else {
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
