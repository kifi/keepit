package com.keepit.curator.commanders.persona

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.curator.model._
import com.keepit.model.{ Persona, User }
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

@Singleton
class PersonaRecommendationIngestor @Inject() (
    db: Database,
    uriPersonaRecoPool: URIPersonaRecommendationPool,
    libPersonaRecoPool: LibraryPersonaRecommendationPool,
    uriRecRepo: UriRecommendationRepo,
    libRecRepo: LibraryRecommendationRepo,
    libMembershipRepo: CuratorLibraryMembershipInfoRepo) {

  def ingestUserRecosByPersonas(userId: Id[User], pids: Seq[Id[Persona]], reverseIngestion: Boolean): Future[Unit] = {
    val uriRecos = pids.map { pid => uriPersonaRecoPool.getUserRecosByPersona(userId, pid) }.flatten.distinct
    val uriIngested = SafeFuture { ingestURIRecos(userId, uriRecos, reverseIngestion) }

    val libRecos = pids.map { pid => libPersonaRecoPool.getUserRecosByPersona(userId, pid) }.flatten.distinct
    val libIngested = SafeFuture { ingestLibraryRecos(userId, libRecos, reverseIngestion) }

    for {
      uri <- uriIngested
      lib <- libIngested
    } yield ()

  }

  private def ingestLibraryReco(libReco: LibraryRecommendation, reverseIngestion: Boolean)(implicit session: RWSession): Unit = {
    libRecRepo.getByLibraryAndUserId(libReco.libraryId, libReco.userId, excludeState = None) match {
      case None => if (!reverseIngestion) libRecRepo.save(libReco)
      case Some(existing) => {
        val state = if (reverseIngestion) LibraryRecommendationStates.INACTIVE else libReco.state
        val toSave = existing.copy(masterScore = libReco.masterScore, allScores = libReco.allScores, state = state)
        libRecRepo.save(toSave)
      }
    }
  }

  private def ingestURIReco(uriReco: UriRecommendation, reverseIngestion: Boolean)(implicit session: RWSession): Unit = {
    uriRecRepo.getByUriAndUserId(uriReco.uriId, uriReco.userId, excludeUriRecommendationState = None) match {
      case None => if (!reverseIngestion) uriRecRepo.save(uriReco)
      case Some(existing) => {
        val state = if (reverseIngestion) UriRecommendationStates.INACTIVE else uriReco.state
        val toSave = existing.copy(masterScore = uriReco.masterScore, allScores = uriReco.allScores, state = state)
        uriRecRepo.save(toSave)
      }
    }
  }

  // optimized for new user
  private def ingestLibraryRecos(userId: Id[User], libRecos: Seq[LibraryRecommendation], reverseIngestion: Boolean): Unit = {
    val (existing, uniqueLibRecosToIngest) = db.readOnlyMaster { implicit s =>
      val userLibs = libMembershipRepo.getLibrariesByUserId(userId).toSet
      val existing = db.readOnlyMaster { implicit s => libRecRepo.getLibraryIdsForUser(userId) }
      val uniqueLibRecosToIngest = libRecos.groupBy(_.libraryId).map { case (id, recos) => recos.head }.toSeq.filter(x => !userLibs.contains(x.libraryId))
      (existing, uniqueLibRecosToIngest)
    }
    if (existing.isEmpty) {
      if (!reverseIngestion) {
        db.readWrite(attempts = 2) { implicit s => libRecRepo.insertAll(uniqueLibRecosToIngest) }
      }
    } else {
      db.readWrite(attempts = 2) { implicit s => uniqueLibRecosToIngest.foreach { ingestLibraryReco(_, reverseIngestion) } }
    }
  }

  // optimized for new user
  private def ingestURIRecos(userId: Id[User], uriRecos: Seq[UriRecommendation], reverseIngestion: Boolean): Unit = {
    val uniqueUriRecos = uriRecos.groupBy(_.uriId).map { case (id, recos) => recos.head }.toSeq
    val existing = db.readOnlyMaster { implicit s => uriRecRepo.getUriIdsForUser(userId) }
    if (existing.isEmpty) {
      if (!reverseIngestion) {
        db.readWrite(attempts = 2) { implicit s => uriRecRepo.insertAll(uniqueUriRecos) }
      }
    } else {
      db.readWrite(attempts = 2) { implicit s => uniqueUriRecos.foreach(ingestURIReco(_, reverseIngestion)) }
    }
  }

}
