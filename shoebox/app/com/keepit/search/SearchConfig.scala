package com.keepit.search

object SearchConfig {
  private val defaultParams =
    Map[String, String](
      "maxMyBookmarks" -> "5",
      "maxTextHitsPerCategory" -> "1000",
      "myBookmarkBoost" -> "2",
      "sharingBoost" -> "0.5",
      "othersBookmarkWeight" -> "0.1")

  val default = new SearchConfig(defaultParams)
  
  def apply(params: Map[String, String]) = {
    new SearchConfig(defaultParams ++ params)
  }
}

class SearchConfig(params: Map[String, String]) {
  def asInt(name: String) = params(name).toInt
  def asFloat(name: String) = params(name).toFloat
  def asString(name: String) = params(name)
}