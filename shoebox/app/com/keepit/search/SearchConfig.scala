package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.User

object SearchConfig {
  private var defaultParams =
    Map[String, String](
      "minMyBookmarks" -> "2",
      "myBookmarkBoost" -> "1.5",
      "personalTitleBoost" -> "1.5",
      "sharingBoostInNetwork" -> "0.5",
      "sharingBoostOutOfNetwork" -> "0.1",
      "percentMatch" -> "75",
      "halfDecayHours" -> "24",
      "recencyBoost" -> "1.0",
      "tailCutting" -> "0.01",
      "proximityBoost" -> "0.5",
      "semanticBoost" -> "0.5",
      "dumpingByRank" -> "true",
      "dumpingHalfDecayMine" -> "8.0",
      "dumpingHalfDecayFriends" -> "6.0",
      "dumpingHalfDecayOthers" -> "2.0"
    )
  private val descriptions =
    Map[String, String](
      "minMyBookmarks" -> "the minimum number of my bookmarks in a search result",
      "myBookmarkBoost" -> "importance my bookmark",
      "personalTitleBoost" -> "boosting the personal bookmark title score when there is no match in the article",
      "sharingBoostInNetwork" -> "importance of the number of friends sharing the bookmark",
      "sharingBoostOutOfNetwork" -> "importance of the number of others sharing the bookmark",
      "percentMatch" -> "the minimum percentage of search terms have to match (weighted by IDF)",
      "halfDecayHours" -> "the time the recency boost becomes half",
      "recencyBoost" -> "importance of the recent bookmarks",
      "tailCutting" -> "after dumping a hit with a score below the high score multiplied by this will be removed",
      "proximityBoost" -> "boosting by proximity",
      "semanticBoost" -> "boosting by semantic vector",
      "dumpingByRank" -> "enable score dumping by rank",
      "dumpingHalfDecayMine" -> "how many top hits in my bookmarks are important",
      "dumpingHalfDecayFriends" -> "how many top hits in friends' bookmarks are important",
      "dumpingHalfDecayOthers" -> "how many top hits in others' bookmark are important"
    )

  var defaultConfig = new SearchConfig(defaultParams)

  def setDefault(binding: (String, String)) = {
    defaultParams += binding
    defaultConfig = new SearchConfig(defaultParams)
    defaultConfig
  }

  def getDefaultConfig = defaultConfig

  def apply(params: Map[String, String]): SearchConfig = defaultConfig(params)
  def apply(newParams: (String, String)*): SearchConfig = apply(newParams.foldLeft(Map.empty[String, String]){ (m, p) => m + p })

  private var userConfig = Map.empty[Long, SearchConfig]
  def getUserConfig(userId: Id[User]) = userConfig.getOrElse(userId.id, defaultConfig)
  def setUserConfig(userId: Id[User], config: SearchConfig) { userConfig = userConfig + (userId.id -> config) }
  def resetUserConfig(userId: Id[User]) { userConfig = userConfig - userId.id }

  def getDescription(name: String) = descriptions.get(name)
}

class SearchConfig(params: Map[String, String]) {
  def asInt(name: String) = params(name).toInt
  def asLong(name: String) = params(name).toLong
  def asFloat(name: String) = params(name).toFloat
  def asDouble(name: String) = params(name).toDouble
  def asBoolean(name: String) = params(name).toBoolean
  def asString(name: String) = params(name)

  def apply(newParams: Map[String, String]): SearchConfig = new SearchConfig(params ++ newParams)
  def apply(newParams: (String, String)*): SearchConfig = apply(newParams.foldLeft(Map.empty[String, String]){ (m, p) => m + p })

  def iterator: Iterator[(String, String)] = params.iterator
}
