package com.keepit.normalizer

import net.codingwell.scalaguice.ScalaModule
import com.keepit.model.NormalizedURI
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.net.URI
import scala.concurrent.Future

case class TestNormalizationServiceModule() extends ScalaModule {
  def configure() {
    bind[NormalizationService].toInstance(PrenormalizationService)
  }
}

object PrenormalizationService extends NormalizationService {
  def update(current: NormalizationReference, candidates: NormalizationCandidate*) = Future.successful(None)
  def normalize(uriString: String)(implicit session: RSession) = prenormalize(uriString)
  def prenormalize(uriString: String)(implicit session: RSession) = Prenormalizer(uriString)
}
