package com.keepit.search

object SearchConfig {
  private var defaultParams =
    Map[String, String](
      "minMyBookmarks" -> "2",
      "maxTextHitsPerCategory" -> "1000",
      "myBookmarkBoost" -> "1.5",
      "sharingBoost" -> "0.5",
      "percentMatch" -> "75",
      "halfDecayHours" -> "24",
      "recencyBoost" -> "1.0",
      "tailCutting" -> "0.5")

  var defaultConfig = new SearchConfig(defaultParams)

  def setDefault(binding: (String, String)) = {
    defaultParams += binding
    defaultConfig = new SearchConfig(defaultParams)
    defaultConfig
  }

  def getDefaultConfig = defaultConfig

  def apply(params: Map[String, String]) = {
    new SearchConfig(defaultParams ++ params)
  }
}

class SearchConfig(params: Map[String, String]) {
  def asInt(name: String) = params(name).toInt
  def asLong(name: String) = params(name).toLong
  def asFloat(name: String) = params(name).toFloat
  def asDouble(name: String) = params(name).toDouble
  def asBoolean(name: String) = params(name).toBoolean
  def asString(name: String) = params(name)
}
