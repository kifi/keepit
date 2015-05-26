package com.keepit.normalizer

import com.keepit.common.core._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.{ Article, LinkedInProfileArticle }
import com.keepit.rover.article.content.{ ArticleContent, NormalizationInfoHolder }
import com.keepit.rover.article.policy.ArticleFetchPolicy
import com.keepit.rover.document.utils.Signature

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.model.Normalization
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import scala.util.Try

trait ContentCheck {
  def getFailedAttempts(): Set[(String, String)]
  def isDefinedAt(candidate: NormalizationCandidate): Boolean
  protected def check(candidate: NormalizationCandidate)(implicit executionContext: ExecutionContext): Future[Boolean]

  def apply(candidate: NormalizationCandidate)(implicit executionContext: ExecutionContext) = {
    if (isDefinedAt(candidate)) check(candidate)
    else throw new IllegalArgumentException(s"${this.getClass.getSimpleName} is not defined for $candidate")
  }
}

case class SignatureCheck(referenceUrl: String, referenceSignature: Option[Signature] = None, trustedDomain: Option[String] = None)(implicit rover: RoverServiceClient, articlePolicy: ArticleFetchPolicy) extends ContentCheck with Logging {

  private val recency = 7 days

  def isDefinedAt(candidate: NormalizationCandidate) = {
    val isTrustedSource = trustedDomain.map(candidate.url.matches) getOrElse candidate.isTrusted
    lazy val isJavaUri = Try(java.net.URI.create(candidate.url)).isSuccess
    isTrustedSource && isJavaUri
  }

  private def signature(url: String): Future[Option[Signature]] = {
    articlePolicy.toBeScraped(url) match {
      case Some(kind) => rover.getOrElseComputeRecentContentSignature(url, recency)(kind)
      case None => Future.successful(None)
    }
  }

  private lazy val referenceContentSignatureFuture = referenceSignature match {
    case None => signature(referenceUrl)
    case someSignature => Future.successful(someSignature)
  }
  private var failedContentChecks = Set.empty[String]
  private var referenceUrlIsBroken = false

  protected def check(candidate: NormalizationCandidate)(implicit executionContext: ExecutionContext): Future[Boolean] = {
    if (referenceUrlIsBroken || failedContentChecks.contains(candidate.url)) Future.successful(false)
    else for {
      currentContentSignatureOption <- referenceContentSignatureFuture
      candidateContentSignatureOption <- if (currentContentSignatureOption.isDefined) signature(candidate.url) else Future.successful(None)
    } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
      case (Some(currentContentSignature), Some(candidateContentSignature)) => {
        val similarity = currentContentSignature.similarTo(candidateContentSignature)
        val threshold = 0.99 // todo(Léo): move to config
        val doTheyMatch = similarity >= threshold
        log.info(s"[${if (doTheyMatch) "ACCEPT" else "REJECT"} at $threshold] Content similarity of ${referenceUrl} and ${candidate.url}: $similarity")
        doTheyMatch
      }
      case (Some(_), None) => {
        log.error(s"Content signature of URL ${candidate.url} could not be computed.")
        failedContentChecks += candidate.url; false
      }
      case (None, _) => {
        log.error(s"Content signature of reference URL ${referenceUrl} could not be computed.")
        referenceUrlIsBroken = true; false
      }
    }
  }

  def getFailedAttempts() = failedContentChecks.map((referenceUrl, _))

}

case class LinkedInProfileCheck(privateProfileId: Long)(implicit rover: RoverServiceClient) extends ContentCheck with Logging {

  private val recency = 30 days

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.normalization == Normalization.CANONICAL && LinkedInNormalizer.linkedInCanonicalPublicProfile.findFirstIn(candidate.url).isDefined
  protected def check(publicProfileCandidate: NormalizationCandidate)(implicit executionContext: ExecutionContext) = {
    rover.getOrElseFetchRecentArticle[LinkedInProfileArticle](publicProfileCandidate.url, recency).map {
      case Some(article) if article.content.profile.id.exists(_ == privateProfileId.toString) => true
      case _ => {
        log.error(s"Content check of LinkedIn public profile ${publicProfileCandidate.url} for id ${privateProfileId} failed.")
        false
      }
    }
  }
  def getFailedAttempts() = Set.empty
}

case class AlternateUrlCheck(referenceUrl: String, prenormalize: String => Try[String])(implicit rover: RoverServiceClient, articlePolicy: ArticleFetchPolicy) extends ContentCheck with Logging {

  private val recency = 30 days

  private var failedContentChecks = Set.empty[String]

  private lazy val futureAlternateUrls: Future[Set[String]] = {
    articlePolicy.toBeScraped(referenceUrl) match {
      case None => Future.successful(Set.empty)
      case Some(articleKind) => rover.getOrElseFetchRecentArticle(referenceUrl, recency)(articleKind).imap { articleOpt =>
        articleOpt.map(_.content).toSet[ArticleContent[_ <: Article]].flatMap {
          case normalizationContent: NormalizationInfoHolder => {
            normalizationContent.normalization.alternateUrls.map(prenormalize(_).toOption).flatten
          }
          case _ => Set.empty[String]
        }
      }
    }
  }

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.isInstanceOf[AlternateCandidate]
  protected def check(alternateCandidate: NormalizationCandidate)(implicit executionContext: ExecutionContext) = futureAlternateUrls.map { alternateUrls =>
    alternateUrls.contains(alternateCandidate.url) tap { isSuccessful => if (!isSuccessful) { failedContentChecks += alternateCandidate.url } }
  }

  def getFailedAttempts() = failedContentChecks.map((referenceUrl, _)).toSet
}