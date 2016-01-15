package com.keepit.normalizer

import com.keepit.common.logging.SlackLogging
import com.keepit.common.net.URI
import com.keepit.model.{ UrlPatternRulesCommander }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.policy.ArticleFetchPolicy
import com.keepit.rover.document.utils.Signature
import com.google.inject.{ Inject, Singleton }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import scala.util.{ Failure, Try }

case class PrenormalizationException(uriString: String, cause: Throwable) extends Exception(s"Failed to prenormalize $uriString", cause)

@Singleton
class PriorNormalizationKnowledge @Inject() (
    urlPatternRules: UrlPatternRulesCommander,
    val inhouseSlackClient: InhouseSlackClient,
    implicit val rover: RoverServiceClient,
    implicit val articlePolicy: ArticleFetchPolicy) extends SlackLogging {

  val loggingDestination = InhouseSlackChannel.TEST_CAM

  def prenormalize(uriString: String): Try[String] = {
    URI.parse(uriString).flatMap { parsedUri =>
      Try { Prenormalizer(parsedUri) }.map { prenormalizedUri =>
        val uriWithPreferredSchemeOption = getPreferredSchemeNormalizer(uriString).map(_.apply(prenormalizedUri))

        if (uriWithPreferredSchemeOption.nonEmpty && !uriWithPreferredSchemeOption.contains(prenormalizedUri))
          slackLog.warn(s"uriString=$uriString, prenormalized=$prenormalizedUri, schemed=$uriWithPreferredSchemeOption")

        val result = uriWithPreferredSchemeOption getOrElse prenormalizedUri
        result.toString()
      }
    }.recoverWith {
      case cause: Throwable => Failure(PrenormalizationException(uriString, cause))
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
