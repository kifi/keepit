package com.keepit.shoebox.rover

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ URI, WebService }
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.rover.fetcher.HttpRedirect
import org.apache.http.HttpStatus
import org.joda.time.DateTime
import com.keepit.common.core._
import scala.concurrent.duration._

import scala.concurrent.{ Future, ExecutionContext }

object HttpRedirectIngestionHelper {
  val recentKeepWindows = 1 hour
}

@Singleton
class HttpRedirectIngestionHelper @Inject() (
    db: Database,
    keepRepo: KeepRepo,
    uriRepo: NormalizedURIRepo,
    ws: WebService,
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext) extends Logging {

  import HttpRedirectIngestionHelper._

  def processRedirects(uriId: Id[NormalizedURI], url: String, redirects: Seq[HttpRedirect], redirectedAt: DateTime): Future[Boolean] = {
    if (redirects.isEmpty) Future.successful(false)
    else {
      log.info(s"Processing redirects found at uri ${uriId}: ${url}: ${redirects.mkString(" -> ")}")
      resolve(uriId, url, redirects, redirectedAt).flatMap {
        case Some(validDestinationUrl) => renormalizeWithPermanentRedirect(uriId, validDestinationUrl)
        case None => {
          log.warn(s"Adding restriction to uri ${uriId}: ${url}: ${redirects.mkString(" -> ")}")
          getUriWithUpdatedRestriction(uriId, redirects.headOption.map(redirect => Restriction.http(redirect.statusCode)))
          Future.successful(false)
        }
      }
    }
  } tap { _.imap { hasBeenRenormalized => if (hasBeenRenormalized) log.info(s"Uri $uriId has been renormalized after processing $redirects from $redirectedAt") } }

  private def renormalizeWithPermanentRedirect(uriId: Id[NormalizedURI], destinationUrl: String): Future[Boolean] = {
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

  private def resolve(uriId: Id[NormalizedURI], url: String, redirects: Seq[HttpRedirect], redirectedAt: DateTime): Future[Option[String]] = {
    val relevantRedirects = redirects.dropWhile(!_.isLocatedAt(url))
    val isActuallyPermanent = relevantRedirects.headOption match {
      case Some(redirect) if redirect.isShortener => Future.successful(true)
      case Some(redirect) if redirect.isPermanent => hasFishy301(uriId, url, redirectedAt)
      case _ => Future.successful(false)
    }

    isActuallyPermanent map {
      case true => HttpRedirect.resolve(url, relevantRedirects).flatMap { candidateUrl =>
        NormalizationCandidateSanitizer.validateCandidateUrl(url, candidateUrl)
      }
      case false => None
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

  private def getUriWithUpdatedRestriction(uriId: Id[NormalizedURI], redirectRestriction: Option[Restriction]): NormalizedURI = {
    db.readWrite { implicit session =>
      val uri = uriRepo.get(uriId)
      val updatedUri = redirectRestriction.map(updateRedirectRestriction(uri, _)) getOrElse removeRedirectRestriction(uri)
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