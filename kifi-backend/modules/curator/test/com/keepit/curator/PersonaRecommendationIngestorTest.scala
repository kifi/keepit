package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.curator.commanders.persona._
import com.keepit.curator.model.{ LibraryRecommendationRepo, UriRecommendationRepo }
import com.keepit.model.{ User, Library, Persona, NormalizedURI }
import org.specs2.mutable.Specification

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

        val ingestor = new PersonaRecommendationIngestor(db, uriPool, libPool, uriRecRepo, libRecRepo)
        ingestor.ingestUserRecosByPersona(Id[User](1), Id[Persona](1))

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
  }

}
