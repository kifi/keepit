package com.keepit.normalizer

import net.codingwell.scalaguice.ScalaModule
import com.keepit.model.{NormalizedURIRepo, NormalizedURI}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.net.URI

case class NormalizationServiceTestModule() extends ScalaModule {
  def configure() {
    bind[NormalizationService].toInstance(PrenormalizationService)
  }
}

object PrenormalizationService extends NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Option[NormalizedURI] = None
  def normalize(uriString: String)(implicit session: RSession): String = URI.safelyParse(uriString).map(Prenormalizer).flatMap(_.safelyToString()).getOrElse(uriString)
}
