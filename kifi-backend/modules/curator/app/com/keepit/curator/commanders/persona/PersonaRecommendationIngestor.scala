package com.keepit.curator.commanders.persona

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ UriRecommendation, LibraryRecommendation, LibraryRecommendationRepo, UriRecommendationRepo }
import com.keepit.model.{ Persona, User }

@Singleton
class PersonaRecommendationIngestor @Inject() (
    db: Database,
    uriPersonaRecoPool: URIPersonaRecommendationPool,
    libPersonaRecoPool: LibraryPersonaRecommendationPool,
    uriRecRepo: UriRecommendationRepo,
    libRecRepo: LibraryRecommendationRepo) {

  def ingestUserRecosByPersona(userId: Id[User], pid: Id[Persona]): Unit = {
    val uriRecos = uriPersonaRecoPool.getUserRecosByPersona(userId, pid)
    db.readWrite { implicit s => uriRecos.foreach(ingestURIReco(_)) }

    val libRecos = libPersonaRecoPool.getUserRecosByPersona(userId, pid)
    db.readWrite { implicit s => libRecos.foreach { ingestLibraryReco(_) } }
  }

  private def ingestLibraryReco(libReco: LibraryRecommendation)(implicit session: RWSession): Unit = {
    val toSave = {
      libRecRepo.getByLibraryAndUserId(libReco.libraryId, libReco.userId, excludeState = None) match {
        case None => libReco
        case Some(existing) => existing.copy(masterScore = libReco.masterScore, allScores = libReco.allScores, state = libReco.state)
      }
    }
    libRecRepo.save(toSave)
  }

  private def ingestURIReco(uriReco: UriRecommendation)(implicit session: RWSession): Unit = {
    val toSave = {
      uriRecRepo.getByUriAndUserId(uriReco.uriId, uriReco.userId, excludeUriRecommendationState = None) match {
        case None => uriRecRepo.save(uriReco)
        case Some(existing) => existing.copy(masterScore = uriReco.masterScore, allScores = uriReco.allScores, state = uriReco.state)
      }
    }
    uriRecRepo.save(toSave)
  }

}
