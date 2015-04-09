package com.keepit.shoebox.rover

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.model.{ Normalization, NormalizedURI, NormalizedURIRepo }
import com.keepit.normalizer._
import com.keepit.rover.article.{ GithubArticle, ArticleKind }
import com.keepit.rover.article.content.NormalizationInfo
import org.apache.commons.lang3.StringEscapeUtils._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }

@Singleton
class NormalizationInfoIngestionHelper @Inject() (
    db: Database,
    uriRepo: NormalizedURIRepo,
    uriInterner: NormalizedURIInterner,
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext) extends Logging {

  def processNormalizationInfo(uriId: Id[NormalizedURI], articleKind: ArticleKind[_], destinationUrl: String, info: NormalizationInfo): Future[Boolean] = {
    val scrapedCandidates = getUriNormalizationsCandidates(articleKind, destinationUrl, info)
    val currentReference = db.readOnlyMaster { implicit session => NormalizationReference(uriRepo.get(uriId)) }
    normalizationService.update(currentReference, scrapedCandidates: _*).map { newReferenceUriIdOption =>
      processAlternateUrls(newReferenceUriIdOption getOrElse uriId, destinationUrl, info)
      newReferenceUriIdOption.isDefined
    }
  } tap { _.imap { hasBeenRenormalized => if (hasBeenRenormalized) log.info(s"Uri $uriId has been renormalized after processing $info found at $destinationUrl") } }

  private def getUriNormalizationsCandidates(articleKind: ArticleKind[_], destinationUrl: String, info: NormalizationInfo): Seq[ScrapedCandidate] = {
    articleKind match {
      case GithubArticle => Seq.empty[ScrapedCandidate] // we don't trust Github's canonical urls
      case _ => Map(
        Normalization.CANONICAL -> info.canonicalUrl,
        Normalization.OPENGRAPH -> info.openGraphUrl
      ).mapValues(_.flatMap(validateCandidateUrl(destinationUrl, _))).collect {
          case (normalization, Some(candidateUrl)) => ScrapedCandidate(candidateUrl, normalization)
        }.toSeq
    }
  }

  private def processAlternateUrls(bestReferenceUriId: Id[NormalizedURI], destinationUrl: String, info: NormalizationInfo): Unit = {
    val alternateUrls = (info.alternateUrls ++ info.shortUrl).flatMap(URI.sanitize(destinationUrl, _).map(_.toString()))
    if (alternateUrls.nonEmpty) {
      val bestReference = db.readOnlyMaster { implicit session =>
        uriRepo.get(bestReferenceUriId)
      }
      bestReference.normalization.map(AlternateCandidate(bestReference.url, _)).foreach { bestCandidate =>
        alternateUrls.foreach { alternateUrlString =>
          val alternateUrl = URI.parse(alternateUrlString).get.toString()
          db.readWrite { implicit session =>
            uriInterner.getByUri(alternateUrl) match {
              case Some(existingUri) if existingUri.id.get == bestReference.id.get => // ignore
              case _ => {
                try {
                  uriInterner.internByUri(alternateUrl, bestCandidate)
                } catch {
                  case ex: Throwable => log.error(s"Failed to intern alternate url $alternateUrl for $bestCandidate")
                }
              }
            }
          }
        }
      }
    }
  }

  private def validateCandidateUrl(destinationUrl: String, candidateUrl: String): Option[String] = {
    URI.sanitize(destinationUrl, candidateUrl).flatMap { parsed =>
      val sanitizedCandidateUrl = parsed.toString()

      // Question marks are allowed in query parameter names and values, but their presence
      // in a canonical URL usually indicates a bad url.
      lazy val hasQuestionMark = (parsed.query.exists(_.params.exists(p => p.name.contains('?') || p.value.exists(_.contains('?')))))

      // A common site error is copying the page URL directly into a canoncial URL tag, escaped an extra time.
      lazy val isEscaped = (sanitizedCandidateUrl.length > destinationUrl.length && unescapeHtml4(sanitizedCandidateUrl) == destinationUrl)

      if (hasQuestionMark || isEscaped) None else Some(sanitizedCandidateUrl)
    }
  }
}
