package com.keepit.rover.article

import com.google.inject.{ Inject, Singleton }
import com.keepit.rover.model.ArticleInfo
import com.keepit.common.core._

import scala.concurrent.Future

trait ArticleFetcher {
  def fetch(info: ArticleInfo): Future[Option[Article]]
}

@Singleton
class ArticleFetcherImpl @Inject() (
    embedlyArticleFetcher: EmbedlyArticleFetcher,
    defaultArticleFetcher: DefaultArticleFetcher,
    youtubeArticleFetcher: YoutubeArticleFetcher,
    linkedInProfileArticleFetcher: LinkedInProfileArticleFetcher,
    githubArticleFetcher: GithubArticleFetcher) extends ArticleFetcher {
  def fetch(info: ArticleInfo): Future[Option[Article]] = {
    info.articleKind match {
      case EmbedlyArticle => embedlyArticleFetcher.fetch(info.url).imap(Some(_))
      case DefaultArticle => defaultArticleFetcher.fetch(info.url, info.lastFetchedAt).imap(_.content)
      case YoutubeArticle => youtubeArticleFetcher.fetch(info.url, info.lastFetchedAt).imap(_.content)
      case LinkedInProfileArticle => linkedInProfileArticleFetcher.fetch(info.url, info.lastFetchedAt).imap(_.content)
      case GithubArticle => githubArticleFetcher.fetch(info.url, info.lastFetchedAt).imap(_.content)
    }
  }
}
