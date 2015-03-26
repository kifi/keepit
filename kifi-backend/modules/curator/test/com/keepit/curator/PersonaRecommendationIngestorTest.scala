package com.keepit.curator

import com.keepit.common.db.{ State, Id }
import com.keepit.curator.commanders.persona._
import com.keepit.curator.model._
import com.keepit.model._
import org.specs2.mutable.Specification
import scala.concurrent.Await
import scala.concurrent.duration._

class PersonaRecommendationIngestorTest extends Specification with CuratorTestInjector {
  "persona recommendation ingestor" should {
    "work" in {
      withDb() { implicit injector =>
        val uriRecRepo = inject[UriRecommendationRepo]
        val libRecRepo = inject[LibraryRecommendationRepo]

        val uriPool = new URIFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[NormalizedURI](1)))
        }
        val libPool = new LibraryFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[Library](1)))
        }

        val ingestor = new PersonaRecommendationIngestor(db, uriPool, libPool, uriRecRepo, libRecRepo, inject[CuratorLibraryMembershipInfoRepo])
        Await.result(ingestor.ingestUserRecosByPersonas(Id[User](1), Seq(Id[Persona](1)), false), FiniteDuration(1, SECONDS))

        db.readOnlyReplica { implicit s =>
          val recos = uriRecRepo.getByUserId(Id[User](1))
          recos.size === 1
          recos.head.uriId.id === 1
          (math.abs(recos.head.masterScore - FixedSetURIReco.fakeMasterScore) <= 1.0) === true // random noise in [0, 1]
        }

        db.readOnlyReplica { implicit s =>
          val recos = libRecRepo.getByUserId(Id[User](1))
          recos.size === 1
          recos.head.libraryId.id === 1
          (math.abs(recos.head.masterScore - FixedSetLibraryReco.fakeMasterScore) <= 1.0) === true // random noise in [0, 1]
        }
      }
    }

    "batch works and dedup" in {
      withDb() { implicit injector =>
        val uriRecRepo = inject[UriRecommendationRepo]
        val libRecRepo = inject[LibraryRecommendationRepo]

        val uriPool = new URIFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[NormalizedURI](1)))
        }
        val libPool = new LibraryFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[Library](1)), Id[Persona](2) -> List(Id[Library](1), Id[Library](2)))
        }

        val ingestor = new PersonaRecommendationIngestor(db, uriPool, libPool, uriRecRepo, libRecRepo, inject[CuratorLibraryMembershipInfoRepo])
        Await.result(ingestor.ingestUserRecosByPersonas(Id[User](1), Seq(Id[Persona](1), Id[Persona](2)), false), FiniteDuration(1, SECONDS))

        db.readOnlyReplica { implicit s =>
          val recos = uriRecRepo.getByUserId(Id[User](1))
          recos.size === 1
          recos.head.uriId.id === 1
          (math.abs(recos.head.masterScore - FixedSetURIReco.fakeMasterScore) <= 1.0) === true
        }

        db.readOnlyReplica { implicit s =>
          val recos = libRecRepo.getByUserId(Id[User](1))
          recos.size === 2
          recos.map(_.libraryId).map { _.id }.toSet === Set(1, 2)
        }
      }
    }

    "do not include user's libraries" in {
      withDb() { implicit injector =>
        val uriRecRepo = inject[UriRecommendationRepo]
        val libRecRepo = inject[LibraryRecommendationRepo]
        val libMemRepo = inject[CuratorLibraryMembershipInfoRepo]
        val info = CuratorLibraryMembershipInfo(userId = Id[User](1), libraryId = Id[Library](1), access = LibraryAccess.OWNER, state = State[CuratorLibraryMembershipInfo]("active"))
        val reco = FixedSetLibraryReco.apply(Id[User](1), Id[Library](100), perturbation = 0f)

        db.readWrite { implicit s =>
          libRecRepo.save(reco)
          libMemRepo.save(info)
        }

        val uriPool = new URIFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[NormalizedURI](1)))
        }

        val libPool = new LibraryFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[Library](1), Id[Library](2))) // 1 will not be ingested. user owns/follows it.
        }

        val ingestor = new PersonaRecommendationIngestor(db, uriPool, libPool, uriRecRepo, libRecRepo, libMemRepo)
        Await.result(ingestor.ingestUserRecosByPersonas(Id[User](1), Seq(Id[Persona](1)), false), FiniteDuration(1, SECONDS))

        db.readOnlyReplica { implicit s =>
          val recos = libRecRepo.getByUserId(Id[User](1))
          recos.size === 2
          recos.map(_.libraryId).map { _.id }.toSet === Set(2, 100) // 100 was old one
        }
      }
    }

    "remove recos if user unselects persona" in {
      withDb() { implicit injector =>
        val uriRecRepo = inject[UriRecommendationRepo]
        val libRecRepo = inject[LibraryRecommendationRepo]

        val uriPool = new URIFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[NormalizedURI](1)))
        }
        val libPool = new LibraryFixedSetPersonaRecoPool {
          override val fixedRecos = Map(Id[Persona](1) -> List(Id[Library](1)), Id[Persona](2) -> List(Id[Library](1), Id[Library](2)))
        }

        // ingest persona 1 & 2
        val ingestor = new PersonaRecommendationIngestor(db, uriPool, libPool, uriRecRepo, libRecRepo, inject[CuratorLibraryMembershipInfoRepo])
        Await.result(ingestor.ingestUserRecosByPersonas(Id[User](1), Seq(Id[Persona](1), Id[Persona](2)), false), FiniteDuration(1, SECONDS))

        // now remove persona 2
        Await.result(ingestor.ingestUserRecosByPersonas(Id[User](1), Seq(Id[Persona](1)), true), FiniteDuration(1, SECONDS))

        db.readOnlyReplica { implicit s =>
          val recos = uriRecRepo.getByUserId(Id[User](1)).filter(_.state == UriRecommendationStates.ACTIVE)
          recos.size === 0
        }

        db.readOnlyReplica { implicit s =>
          val recos = libRecRepo.getByUserId(Id[User](1)).filter(_.state == LibraryRecommendationStates.ACTIVE)
          recos.size === 1
          recos.map(_.libraryId).map { _.id }.toSet === Set(2)
        }

      }
    }
  }

}
