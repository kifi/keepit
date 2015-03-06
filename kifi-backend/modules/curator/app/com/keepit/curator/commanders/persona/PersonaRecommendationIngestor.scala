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

  def ingestUserRecosByPersona(userId: Id[User], pid: Id[Persona]): Unit = {
    val uriRecos = uriPersonaRecoPool.getUserRecosByPersona(userId, pid)
    ingestURIRecos(userId, uriRecos)

    val libRecos = libPersonaRecoPool.getUserRecosByPersona(userId, pid)
    ingestLibraryRecos(userId, libRecos)
  }

  def ingestUserRecosByPersonas(userId: Id[User], pids: Seq[Id[Persona]]): Future[Unit] = {
    val uriRecos = pids.map { pid => uriPersonaRecoPool.getUserRecosByPersona(userId, pid) }.flatten.distinct
    val uriIngested = SafeFuture { ingestURIRecos(userId, uriRecos) }

    val libRecos = pids.map { pid => libPersonaRecoPool.getUserRecosByPersona(userId, pid) }.flatten.distinct
    val libIngested = SafeFuture { ingestLibraryRecos(userId, libRecos) }

    for {
      uri <- uriIngested
      lib <- libIngested
    } yield ()

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

  // optimized for new user
  private def ingestLibraryRecos(userId: Id[User], libRecos: Seq[LibraryRecommendation]): Unit = {
    val (existing, uniqueLibRecosToIngest) = db.readOnlyMaster { implicit s =>
      val userLibs = libMembershipRepo.getLibrariesByUserId(userId).toSet
      val existing = db.readOnlyMaster { implicit s => libRecRepo.getLibraryIdsForUser(userId) }
      val uniqueLibRecosToIngest = libRecos.groupBy(_.libraryId).map { case (id, recos) => recos.head }.toSeq.filter(x => !userLibs.contains(x.libraryId))
      (existing, uniqueLibRecosToIngest)
    }
    if (existing.isEmpty) {
      db.readWrite { implicit s => libRecRepo.insertAll(uniqueLibRecosToIngest) }
    } else {
      db.readWrite { implicit s => uniqueLibRecosToIngest.foreach { ingestLibraryReco(_) } }
    }
  }

  // optimized for new user
  private def ingestURIRecos(userId: Id[User], uriRecos: Seq[UriRecommendation]): Unit = {
    val uniqueUriRecos = uriRecos.groupBy(_.uriId).map { case (id, recos) => recos.head }.toSeq
    val existing = db.readOnlyMaster { implicit s => uriRecRepo.getUriIdsForUser(userId) }
    if (existing.isEmpty) {
      db.readWrite { implicit s => uriRecRepo.insertAll(uniqueUriRecos) }
    } else {
      db.readWrite { implicit s => uniqueUriRecos.foreach(ingestURIReco(_)) }
    }
  }

}
