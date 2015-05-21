package com.keepit.normalizer

import com.keepit.common.net.URI
import com.keepit.model.{ UrlPatternRulesCommander }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.policy.ArticleFetchPolicy
import com.keepit.rover.document.utils.Signature
import com.google.inject.{ Inject, Singleton }
import scala.util.{ Failure, Try }

case class PrenormalizationException(cause: Throwable) extends Exception(cause)

@Singleton
class PriorNormalizationKnowledge @Inject() (
    urlPatternRules: UrlPatternRulesCommander,
    implicit val rover: RoverServiceClient,
    implicit val articlePolicy: ArticleFetchPolicy) {

  def prenormalize(uriString: String): Try[String] = {
    URI.parse(uriString).flatMap { parsedUri =>
      Try { Prenormalizer(parsedUri) }.map { prenormalizedUri =>
        val uriWithPreferredSchemeOption = getPreferredSchemeNormalizer(uriString).map(_.apply(prenormalizedUri))
        val result = uriWithPreferredSchemeOption getOrElse prenormalizedUri
        result.toString()
      }
    }.recoverWith {
      case cause: Throwable => Failure(PrenormalizationException(cause))
    }
  }

  def getContentChecks(referenceUrl: String, referenceSignature: Option[Signature] = None): Seq[ContentCheck] = {
    referenceUrl match {
      case LinkedInNormalizer.linkedInPrivateProfile(_, id) => Seq(LinkedInProfileCheck(id.toLong))
      case _ => Seq(
        SignatureCheck(referenceUrl, referenceSignature, urlPatternRules.rules().getTrustedDomain(referenceUrl)),
        AlternateUrlCheck(referenceUrl, prenormalize)
      )
    }
  }

  private def getPreferredSchemeNormalizer(url: String): Option[StaticNormalizer] = urlPatternRules.rules().getPreferredNormalization(url).map(SchemeNormalizer(_))

}
