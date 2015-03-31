package com.keepit.rover.article

import com.google.inject.{ Inject, Singleton }
import com.keepit.rover.article.content.ArticleContent
import com.keepit.rover.fetcher.FetchResult
import com.keepit.rover.model.ArticleKey
import com.keepit.rover.store.RoverArticleStore
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }

case class ArticleFetchRequest[A <: Article](kind: ArticleKind[A], url: String, lastFetchedAt: Option[DateTime], latestArticleKey: Option[ArticleKey[A]])

trait ArticleFetcher[A <: Article] {
  def fetch(request: ArticleFetchRequest[A])(implicit ec: ExecutionContext): Future[Option[A]]
}

object ArticleFetcher {
  def resolveAndCompare[A <: Article](futureFetchedArticle: Future[FetchResult[A]], futureLatestArticle: Future[Option[A]], areSimilar: (A, A) => Boolean)(implicit ec: ExecutionContext): Future[Option[A]] = {
    futureFetchedArticle.flatMap { result =>
      result.resolve match {
        case Failure(error) => Future.failed(error)
        case Success(None) => Future.successful(None)
        case Success(Some(fetchedArticle)) => futureLatestArticle.map {
          case Some(latestArticle) if areSimilar(fetchedArticle, latestArticle) => None
          case _ => Some(fetchedArticle)
        }
      }
    }
  }

  def resolveAndCompare[A <: Article](store: RoverArticleStore)(futureFetchedArticle: Future[FetchResult[A]], latestArticleKey: Option[ArticleKey[A]], areSimilar: (A, A) => Boolean)(implicit classTag: ClassTag[A], ec: ExecutionContext): Future[Option[A]] = {
    val futureLatestArticle = latestArticleKey match {
      case None => Future.successful(None)
      case Some(key) => store.get(key)
    }
    resolveAndCompare(futureFetchedArticle, futureLatestArticle, areSimilar)
  }

  private val defaultSimilarityThreshold = 0.75
  def defaultSimilarityCheck[A <: Article](thisArticle: A, thatArticle: A): Boolean = {
    val thisArticleSignature = ArticleContent.defaultSignature(thisArticle.content)
    val thatArticleSignature = ArticleContent.defaultSignature(thatArticle.content)
    val similarity = thisArticleSignature similarTo thatArticleSignature
    similarity > defaultSimilarityThreshold
  }
}

@Singleton
class ArticleFetcherProvider @Inject() (
    embedlyArticleFetcher: EmbedlyArticleFetcher,
    defaultArticleFetcher: DefaultArticleFetcher,
    youtubeArticleFetcher: YoutubeArticleFetcher,
    linkedInProfileArticleFetcher: LinkedInProfileArticleFetcher,
    githubArticleFetcher: GithubArticleFetcher) {

  def get[A <: Article](implicit kind: ArticleKind[A]): ArticleFetcher[A] = kind match {
    case EmbedlyArticle => embedlyArticleFetcher
    case DefaultArticle => defaultArticleFetcher
    case YoutubeArticle => youtubeArticleFetcher
    case LinkedInProfileArticle => linkedInProfileArticleFetcher
    case GithubArticle => githubArticleFetcher
  }

  def fetch[A <: Article](request: ArticleFetchRequest[A])(implicit ec: ExecutionContext): Future[Option[A]] = get(request.kind).fetch(request)
}
