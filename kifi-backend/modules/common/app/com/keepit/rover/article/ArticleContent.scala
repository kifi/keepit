package com.keepit.rover.article

import com.keepit.model.PageAuthor
import org.joda.time.DateTime

trait ArticleContent {
  def title: Option[String]
  def description: Option[String]
  def content: Option[String]
  def keywords: Seq[String]
  def authors: Seq[PageAuthor]
  def publishedAt(): Option[DateTime]
}
