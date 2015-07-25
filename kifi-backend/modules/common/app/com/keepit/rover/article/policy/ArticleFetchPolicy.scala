package com.keepit.rover.article.policy

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.{ Host, URI }
import com.keepit.model.IndexableUri
import com.keepit.model.NormalizedURIStates._
import com.keepit.rover.article._
import com.keepit.rover.article.content.LinkedInProfile

@Singleton
class ArticleFetchPolicy @Inject() () {

  def toBeInterned(uri: IndexableUri): Set[ArticleKind[_ <: Article]] = {
    uri.state match {
      case INACTIVE | REDIRECTED => Set.empty
      case _ if uri.shouldHaveContent => toBeInterned(uri.url)
      case _ => Set.empty
    }
  }

  def toBeDeactivated(uri: IndexableUri): Set[ArticleKind[_ <: Article]] = {
    uri.state match {
      case INACTIVE => ArticleKind.all
      case _ => Set.empty
    }
  }

  def toBeInterned(url: String): Set[ArticleKind[_ <: Article]] = Set(EmbedlyArticle) ++ toBeScraped(url)

  def toBeScraped(url: String): Option[ArticleKind[_ <: Article]] = URI.parse(url).toOption.map {
    case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) if path.endsWith("/watch") && query.containsParam("v") => YoutubeArticle
    case URI(_, _, Some(Host("com", "github", _*)), _, _, _, _) => GithubArticle
    case URI(_, _, Some(Host("com", "linkedin", _*)), _, _, _, _) if LinkedInProfile.url.findFirstIn(url).isDefined => LinkedInProfileArticle
    case _ => DefaultArticle
  }
}
