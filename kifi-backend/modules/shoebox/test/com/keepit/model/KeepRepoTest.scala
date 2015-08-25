package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.test._
import org.joda.time.DateTime
import org.specs2.mutable._

class KeepRepoTest extends Specification with ShoeboxTestInjector {

  "KeepRepo" should {
    "save and load a keep" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          val savedKeep = keepRepo.save(Keep(
            uriId = Id[NormalizedURI](1),
            isPrimary = true,
            url = "http://www.kifi.com",
            visibility = LibraryVisibility.ORGANIZATION,
            userId = Id[User](3),
            source = KeepSource.keeper,
            libraryId = Some(Id[Library](4)),
            connectionsHash = Some(KeepConnectionsHash(5))
          ))
          val cacheKeep = keepRepo.get(savedKeep.id.get)
          val dbKeep = keepRepo.getNoCache(savedKeep.id.get)
          cacheKeep === dbKeep

          // The savedKeep is not equal to the dbKeep because of originalKeeperId
          // If you can figure out a way to have keepRepo.save give back the correct model, I will be so happy
          // -- Ryan
          def f(k: Keep) = (k.id.get, k.uriId, k.isPrimary, k.url, k.visibility, k.userId, k.source, k.libraryId, k.connectionsHash)
          f(dbKeep) === f(savedKeep)
        }
        1 === 1
      }
    }
    "getPrivate" in {
      withDb() { implicit injector =>
        val (user1, user2, keep1, keep2, keep3) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved

          val user1MainLib = LibraryFactory.library().withOwner(user1).discoverable().saved
          val user1PrivateLib = LibraryFactory.library().withOwner(user1).secret().saved
          val user1PublicLib = LibraryFactory.library().withOwner(user1).published().saved

          val user2MainLib = LibraryFactory.library().withOwner(user2).discoverable().saved
          val user2PrivateLib = LibraryFactory.library().withOwner(user2).secret().saved
          val user2PublicLib = LibraryFactory.library().withOwner(user2).published().saved

          val user1Keeps = keep().withUser(user1).withLibrary(user1MainLib).saved ::
            keep().withUser(user1).withLibrary(user1PrivateLib).saved ::
            keep().withUser(user1).withLibrary(user1PublicLib).saved :: Nil
          keeps(20).map(_.withUser(user2).withLibrary(user2PublicLib)).saved
          keeps(20).map(_.withUser(user2).withLibrary(user2PrivateLib)).saved
          (user1, user2, user1Keeps(0), user1Keeps(1), user1Keeps(2))
        }
        db.readOnlyMaster { implicit s =>
          val privates = inject[KeepRepo].getPrivate(user1.id.get, 0, 10)
          privates.map(_.id.get) === Seq(keep2.id.get)
          val public = inject[KeepRepo].getNonPrivate(user1.id.get, 0, 10)
          public.map(_.id.get) === Seq(keep3.id.get, keep1.id.get)
          inject[KeepRepo].getPrivate(user2.id.get, 0, 8).size === 8
          inject[KeepRepo].getPrivate(user2.id.get, 10, 100).size === 10
          inject[KeepRepo].getNonPrivate(user2.id.get, 0, 8).size === 8
        }
      }
    }
    "getCountByLibrariesSince" in {
      withDb() { implicit injector =>
        val date = inject[FakeClock].now
        val (lib1, lib2, lib3, lib4, lib5, lib6) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved

          val lib1 = LibraryFactory.library().withOwner(user1).discoverable().saved
          val lib2 = LibraryFactory.library().withOwner(user1).secret().saved
          val lib3 = LibraryFactory.library().withOwner(user1).published().saved

          val lib4 = LibraryFactory.library().withOwner(user2).discoverable().saved
          val lib5 = LibraryFactory.library().withOwner(user2).secret().saved
          val lib6 = LibraryFactory.library().withOwner(user2).published().saved

          keep().withUser(user1).withKeptAt(date.plusDays(7)).withLibrary(lib1).saved
          keep().withUser(user1).withKeptAt(date).withLibrary(lib2).saved
          keep().withUser(user1).withKeptAt(date.minusDays(7)).withLibrary(lib3).saved
          keeps(20).map(_.withUser(user2).withLibrary(lib6).withKeptAt(date)).saved
          keeps(20).map(_.withUser(user2).withLibrary(lib5).withKeptAt(date)).saved
          (lib1.id.get, lib2.id.get, lib3.id.get, lib4.id.get, lib5.id.get, lib6.id.get)
        }
        db.readOnlyMaster { implicit s =>
          inject[KeepRepo].getCountByLibrariesSince(Set(lib1, lib2, lib3), date.plusMinutes(1)) === 1
          inject[KeepRepo].getCountByLibrariesSince(Set(lib1, lib2, lib3), date.minusMinutes(1)) === 2
          inject[KeepRepo].getCountByLibrariesSince(Set(lib1, lib2, lib3), date.plusYears(1)) === 0
          inject[KeepRepo].getCountByLibrariesSince(Set(lib1, lib2, lib3), date.minusYears(1)) === 3
        }
      }
    }
    "last active keep time" in {
      withDb() { implicit injector =>
        val (user1, user2, user3) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved

          val user1MainLib = LibraryFactory.library().withOwner(user1).discoverable().saved
          val user2MainLib = LibraryFactory.library().withOwner(user2).discoverable().saved
          keep().withUser(user1).withKeptAt(new DateTime(2013, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)).withLibrary(user1MainLib).saved
          keep().withUser(user1).withKeptAt(new DateTime(2014, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)).withLibrary(user1MainLib).saved
          keep().withUser(user1).withKeptAt(new DateTime(2015, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)).withLibrary(user1MainLib).saved
          keep().withUser(user2).withLibrary(user2MainLib).saved
          (user1, user2, user3)
        }
        db.readOnlyMaster { implicit s =>
          inject[KeepRepo].latestManualKeepTime(user1.id.get).get.year().get() === 2015
          inject[KeepRepo].latestManualKeepTime(user3.id.get) === None
        }
      }
    }

    "most recent from followed libraries" in { //tests only that the query interpolates correctly
      withDb() { implicit injector =>
        db.readWrite { implicit s =>
          val user1 = user().saved
          inject[LibraryMembershipRepo]
          inject[KeepRepo].getRecentKeepsFromFollowedLibraries(user1.id.get, 10, None, None)
          1 === 1
        }
      }
    }

    "keeps by library without orgId" in {
      withDb() { implicit injector =>
        val orgId1 = Some(Id[Organization](1))
        val orgId2 = Some(Id[Organization](2))
        val library = db.readWrite { implicit s =>
          val lib = libraryRepo.save(Library(name = "Kifi", ownerId = Id[User](1), visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("slug"), memberCount = 1))
          keeps(20).map(_.withLibrary(lib).withOrganizationId(orgId1)).saved
          keeps(20).map(_.withLibrary(lib).withOrganizationId(orgId2)).saved
          keeps(10).map(_.withLibrary(lib)).saved
          lib
        }

        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(library.id.get, 0, 50) }.length === 50

        val keepsWithOrgIdSet = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibraryWithInconsistentOrgId(library.id.get, None, Limit(50))
        }

        keepsWithOrgIdSet.size === 40

        val keepsWithoutOrgId1 = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibraryWithInconsistentOrgId(library.id.get, orgId1, Limit(50))
        }
        keepsWithoutOrgId1.size === 30
      }
    }

  }

}
