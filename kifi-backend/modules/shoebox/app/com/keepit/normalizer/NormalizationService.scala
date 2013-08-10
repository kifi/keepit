package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.model.{UriNormalizationRuleRepo, NormalizedURI}
import com.keepit.common.logging.Logging

trait URINormalizer extends PartialFunction[URI, URI]

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (normalizationRuleRepo: UriNormalizationRuleRepo) extends NormalizationService with Logging {
  val normalizers = Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def normalize(uriString: String)(implicit session: RSession): String = {
    val prepUrl = for {
      uri <- URI.safelyParse(uriString)
      prepUri <- normalizers.find(_.isDefinedAt(uri)).map(_.apply(uri))
      prepUrl <- prepUri.safelyToString()
    } yield prepUrl

    val mappedUrl = for {
      prepUrl <- prepUrl
      mappedUrl <- normalizationRuleRepo.getByUrl(prepUrl)
    } yield mappedUrl

    mappedUrl.getOrElse(prepUrl.getOrElse(uriString))
  }

 def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI = current

}
