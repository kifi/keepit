package com.keepit.shoebox.rover

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ WebService, URI }
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.rover.article.content.NormalizationInfo
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.rover.model.ShoeboxArticleUpdate
import org.apache.http.HttpStatus
import org.joda.time.DateTime
import scala.concurrent.duration._

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.core._

object ArticleUpdateNormalizer {
  val recentKeepWindows = 1 hour
}

class ArticleUpdateNormalizer @Inject() (
    db: Database,
    keepRepo: KeepRepo,
    uriRepo: NormalizedURIRepo,
    uriInterner: NormalizedURIInterner,
    ws: WebService,
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext) extends Logging {

  import ArticleUpdateNormalizer._

  def processUpdate(update: ShoeboxArticleUpdate): Future[Unit] = {
    val hasBeenRedirected = update.httpInfo match {
      case None => Future.successful(false)
      case Some(httpInfo) => processRedirects(update.uriId, update.url, httpInfo.redirects, update.createdAt)
    }
    hasBeenRedirected map {
      case true => Future.successful(())
      case false => update.normalizationInfo match {
        case Some(normalizationInfo) => processNormalizationInfo(update.uriId, normalizationInfo)
        case None => Future.successful(())
      }
    }
  }

  private def processNormalizationInfo(uriId: Id[NormalizedURI], info: NormalizationInfo): Future[Unit] = {
    val scrapedCandidates = Seq(
      info.canonicalUrl.map(ScrapedCandidate(_, Normalization.CANONICAL)),
      info.openGraphUrl.map(ScrapedCandidate(_, Normalization.OPENGRAPH))
    ).flatten
    val currentReference = db.readOnlyMaster { implicit session => NormalizationReference(uriRepo.get(uriId)) }

    normalizationService.update(currentReference, scrapedCandidates: _*).map { newReferenceOption =>
      val alternateUrls = info.alternateUrls ++ info.shortUrl
      if (alternateUrls.nonEmpty) {
        val bestReference = db.readOnlyMaster { implicit session =>
          uriRepo.get(newReferenceOption getOrElse uriId)
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
  }

  private def processRedirects(uriId: Id[NormalizedURI], url: String, redirects: Seq[HttpRedirect], redirectedAt: DateTime): Future[Boolean] = {
    if (redirects.isEmpty) Future.successful(false)
    else {
      log.info(s"Processing redirects found at uri ${uriId}: ${url}: ${redirects.mkString("\n")}")
      resolve(uriId, url, redirects, redirectedAt).flatMap {
        case Left(Some(destinationUrl)) => recordPermanentRedirect(uriId, destinationUrl)
        case Left(None) =>
          log.warn(s"Unable to resolve absolute permanent redirect at uri ${uriId}: ${url}: ${redirects.mkString("\n")}")
          Future.successful(false)
        case Right(restriction) =>
          getUriWithUpdatedRestriction(uriId, restriction)
          Future.successful(false)
      }
    }
  }

  private def recordPermanentRedirect(uriId: Id[NormalizedURI], destinationUrl: String): Future[Boolean] = {
    val uriWithverifiedCandidateOption = normalizationService.prenormalize(destinationUrl).toOption.flatMap { prenormalizedDestinationUrl =>
      db.readWrite { implicit session =>
        val (candidateUrl, candidateNormalizationOption) = uriRepo.getByNormalizedUrl(prenormalizedDestinationUrl) match {
          case None => (prenormalizedDestinationUrl, SchemeNormalizer.findSchemeNormalization(prenormalizedDestinationUrl))
          case Some(referenceUri) if referenceUri.state != NormalizedURIStates.REDIRECTED => (referenceUri.url, referenceUri.normalization)
          case Some(reverseRedirectUri) if reverseRedirectUri.redirect == Some(uriId) =>
            (reverseRedirectUri.url, SchemeNormalizer.findSchemeNormalization(reverseRedirectUri.url))
          case Some(redirectedUri) =>
            val referenceUri = uriRepo.get(redirectedUri.redirect.get)
            (referenceUri.url, referenceUri.normalization)
        }
        candidateNormalizationOption.map { candidateNormalization =>
          val unrestrictedUri = getUriWithUpdatedRestriction(uriId, None)
          (unrestrictedUri, VerifiedCandidate(candidateUrl, candidateNormalization))
        }
      }
    }

    uriWithverifiedCandidateOption match {
      case None => Future.successful(false)
      case Some((uri, verifiedCandidate)) =>
        val toBeRedirected = NormalizationReference(uri, correctedNormalization = Some(Normalization.MOVED))
        normalizationService.update(toBeRedirected, verifiedCandidate).imap(_.isDefined)
    }
  }

  private def resolve(uriId: Id[NormalizedURI], url: String, redirects: Seq[HttpRedirect], redirectedAt: DateTime): Future[Either[Option[String], Option[Restriction]]] = {
    val relevantRedirects = redirects.dropWhile(!_.isLocatedAt(url))
    relevantRedirects.headOption match {
      case Some(redirect) if redirect.isShortener => Future.successful(Left(HttpRedirect.resolve(url, relevantRedirects)))
      case Some(redirect) if redirect.isPermanent =>
        hasFishy301(uriId, url, redirectedAt).map {
          case false => Left(HttpRedirect.resolve(url, relevantRedirects))
          case true => Right(Some(Restriction.http(redirect.statusCode)))
        }
      case Some(temporaryRedirect) => Future.successful(Right(Some(Restriction.http(temporaryRedirect.statusCode))))
      case None => Future.successful(Right(None))
    }
  }

  private def hasFishy301(uriId: Id[NormalizedURI], url: String, redirectedAt: DateTime): Future[Boolean] = {
    val existingRestriction = db.readOnlyMaster { implicit session => uriRepo.get(uriId).restriction }
    if (existingRestriction == Some(Restriction.http(301))) {
      Future.successful(true)
    } else {
      val recentKeeps: Set[Keep] = db.readOnlyReplica { implicit session =>
        keepRepo.getKeepsByTimeWindow(uriId, url, keptBefore = redirectedAt, keptAfter = redirectedAt.minusSeconds(recentKeepWindows.toSeconds.toInt))
      }
      FutureHelpers.exists(recentKeeps) {
        case recentKeep if !KeepSource.bulk.contains(recentKeep.source) => Future.successful(true)
        case importedBookmark =>
          val parsedBookmarkUrl = URI.parse(importedBookmark.url).get.toString
          if (parsedBookmarkUrl == url) Future.successful(false)
          else ws.status(parsedBookmarkUrl, followRedirects = false).imap(_ == HttpStatus.SC_MOVED_PERMANENTLY)
      }
    }
  }

  private def getUriWithUpdatedRestriction(uriId: Id[NormalizedURI], restriction: Option[Restriction]): NormalizedURI = {
    db.readWrite { implicit session =>
      val uri = uriRepo.get(uriId)
      val updatedUri = restriction.map(updateRedirectRestriction(uri, _)) getOrElse removeRedirectRestriction(uri)
      if (updatedUri == uri) uri else uriRepo.save(updatedUri)
    }
  }

  private def removeRedirectRestriction(uri: NormalizedURI): NormalizedURI = uri.restriction match {
    case Some(restriction) if Restriction.redirects.contains(restriction) => uri.copy(restriction = None)
    case _ => uri
  }

  private def updateRedirectRestriction(uri: NormalizedURI, restriction: Restriction): NormalizedURI = {
    if (Restriction.redirects.contains(restriction)) uri.copy(restriction = Some(restriction)) else removeRedirectRestriction(uri)
  }

}
