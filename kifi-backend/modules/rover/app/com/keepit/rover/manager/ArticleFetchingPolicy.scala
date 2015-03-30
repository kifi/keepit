package com.keepit.rover.manager

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.State
import com.keepit.common.net.{ Host, URI }
import com.keepit.model.{ NormalizedURIStates, NormalizedURI }
import com.keepit.rover.article._
import com.keepit.rover.article.content.LinkedInProfile

@Singleton
class ArticleFetchingPolicy @Inject() () {

  def toBeInterned(url: String, state: State[NormalizedURI]): Set[ArticleKind[_ <: Article]] = {
    toBeFetched(url) -- toBeDeactivated(state)
  }

  def toBeDeactivated(uriState: State[NormalizedURI]): Set[ArticleKind[_ <: Article]] = {
    import NormalizedURIStates._
    uriState match {
      case INACTIVE | REDIRECTED => ArticleKind.all
      case UNSCRAPABLE => ArticleKind.all - EmbedlyArticle
      case _ => Set.empty
    }
  }

  private def toBeFetched(url: String): Set[ArticleKind[_ <: Article]] = toBeScraped(url).toSet + EmbedlyArticle

  private def toBeScraped(url: String): Option[ArticleKind[_ <: Article]] = URI.parse(url).toOption.map {
    case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) if path.endsWith("/watch") && query.containsParam("v") => YoutubeArticle
    case URI(_, _, Some(Host("com", "github", _*)), _, Some(path), Some(query), _) => GithubArticle
    case URI(_, _, Some(Host("com", "linkedin", _*)), _, Some(path), Some(query), _) if LinkedInProfile.url.findFirstIn(url).isDefined => LinkedInProfileArticle
    case _ => DefaultArticle
  }
}
