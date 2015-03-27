package com.keepit.rover.manager

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.net.{ Host, URI }
import com.keepit.rover.article._
import com.keepit.rover.article.content.LinkedInProfile

@Singleton
class ArticleFetchingPolicy @Inject() () extends Function[String, Set[ArticleKind[_ <: Article]]] {
  def apply(url: String): Set[ArticleKind[_ <: Article]] = getFetchedArticleKind(url).toSet + EmbedlyArticle // We always want Embedly content
  private def getFetchedArticleKind(url: String): Option[ArticleKind[_ <: Article]] = URI.parse(url).toOption.map {
    case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) if path.endsWith("/watch") && query.containsParam("v") => YoutubeArticle
    case URI(_, _, Some(Host("com", "github", _*)), _, Some(path), Some(query), _) => GithubArticle
    case URI(_, _, Some(Host("com", "linkedin", _*)), _, Some(path), Some(query), _) if LinkedInProfile.url.findFirstIn(url).isDefined => LinkedInProfileArticle
    case _ => DefaultArticle
  }
}
