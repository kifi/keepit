package com.keepit.rover.document.utils

object LinkClassifier {
  private val aboutText = Set("about", "about us", "a propos")
  private val aboutUrlRegex = {
    val concatenators = Set("", "-", "_")
    val aboutUrls = for { text <- aboutText; c <- concatenators } yield text.mkString(c)
    ("/(" + aboutUrls.mkString("|") + """)(\.[a-z]{2,4})?/?$""").r
  }
  def isAbout(baseUrl: String, linkText: String, linkUrl: String): Boolean = {
    linkUrl.startsWith(baseUrl) && (aboutText.contains(linkText.toLowerCase) || aboutUrlRegex.pattern.matcher(linkUrl).find)
  }
}
