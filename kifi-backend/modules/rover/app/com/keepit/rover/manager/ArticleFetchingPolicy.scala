package com.keepit.rover.manager

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.State
import com.keepit.common.net.{ Host, URI }
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import com.keepit.rover.article._
import com.keepit.rover.article.content.LinkedInProfile

@Singleton
class ArticleFetchingPolicy @Inject() () {

  def toBeInterned(url: String, uriState: State[NormalizedURI]): Set[ArticleKind[_ <: Article]] = {
    uriState match {
      case ACTIVE | INACTIVE | REDIRECTED => Set.empty
      case UNSCRAPABLE => Set(EmbedlyArticle)
      case SCRAPED | SCRAPE_FAILED => Set(EmbedlyArticle) ++ toBeScraped(url)
    }
  }

  def toBeDeactivated(uriState: State[NormalizedURI]): Set[ArticleKind[_ <: Article]] = {
    uriState match {
      case INACTIVE | REDIRECTED => ArticleKind.all
      case UNSCRAPABLE => ArticleKind.all - EmbedlyArticle
      case ACTIVE | SCRAPED | SCRAPE_FAILED => Set.empty
    }
  }

  private def toBeScraped(url: String): Option[ArticleKind[_ <: Article]] = URI.parse(url).toOption.map {
    case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) if path.endsWith("/watch") && query.containsParam("v") => YoutubeArticle
    case URI(_, _, Some(Host("com", "github", _*)), _, Some(path), Some(query), _) => GithubArticle
    case URI(_, _, Some(Host("com", "linkedin", _*)), _, Some(path), Some(query), _) if LinkedInProfile.url.findFirstIn(url).isDefined => LinkedInProfileArticle
    case _ => DefaultArticle
  }
}
