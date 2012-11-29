package com.keepit.search

object SearchConfig {
  private var defaultParams =
    Map[String, String](
      "minMyBookmarks" -> "2",
      "myBookmarkBoost" -> "1.5",
      "sharingBoost" -> "0.5",
      "percentMatch" -> "75",
      "halfDecayHours" -> "24",
      "recencyBoost" -> "1.0",
      "tailCutting" -> "0.01",
      "proximityBoost" -> "0.5",
      "semanticBoost" -> "0.5",
      "dumpingByRank" -> "true")

  var defaultConfig = new SearchConfig(defaultParams)

  def setDefault(binding: (String, String)) = {
    defaultParams += binding
    defaultConfig = new SearchConfig(defaultParams)
    defaultConfig
  }

  def getDefaultConfig = defaultConfig

  def apply(params: Map[String, String]): SearchConfig = defaultConfig(params)
  def apply(newParams: (String, String)*): SearchConfig = apply(newParams.foldLeft(Map.empty[String, String]){ (m, p) => m + p })
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
}
