package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model.{ NormalizedURIRepo, NormalizedURI }

import scala.concurrent.Future

class NormalizedURICommander @Inject() (
    normalizedURIRepo: NormalizedURIRepo,
    db: Database) extends Logging {

  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    db.readOnlyReplicaAsync { implicit session =>
      val unrestrictedURIs = normalizedURIRepo.checkUnrestrictedURIs(uris)
      val scrapedUris = normalizedURIRepo.checkScrapedURIs(uris)
      (unrestrictedURIs zip scrapedUris).map(p => p._1 && p._2)
    }
  }

}
