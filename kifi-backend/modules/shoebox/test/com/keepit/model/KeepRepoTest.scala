package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.test._
import org.joda.time.DateTime
import org.specs2.mutable._

class KeepRepoTest extends Specification with ShoeboxTestInjector {

  "KeepRepo" should {
    "getPrivate" in {
      withDb() { implicit injector =>
        val (user1, user2, keep1, keep2, keep3) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          val user1Keeps = keep().withUser(user1).discoverable().saved ::
            keep().withUser(user1).secret().saved ::
            keep().withUser(user1).published().saved :: Nil
          keeps(20).map(_.withUser(user2).published()).saved
          keeps(20).map(_.withUser(user2).secret()).saved
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
    "last active keep time" in {
      withDb() { implicit injector =>
        val (user1, user2, user3) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          keep().withUser(user1).withKeptAt(new DateTime(2013, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)).discoverable().saved
          keep().withUser(user1).withKeptAt(new DateTime(2014, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)).discoverable().saved
          keep().withUser(user1).withKeptAt(new DateTime(2015, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)).discoverable().saved
          keep().withUser(user2).discoverable().saved
          (user1, user2, user3)
        }
        db.readOnlyMaster { implicit s =>
          inject[KeepRepo].latestKeep(user1.id.get).get.year().get() === 2015
          inject[KeepRepo].latestKeep(user3.id.get) === None
        }
      }
    }

    "most recent from followed libraries" in { //tests only that the query interpolates correctly
      withDb() { implicit injector =>
        db.readWrite { implicit s =>
          val user1 = user().saved
          inject[LibraryMembershipRepo]
          inject[KeepRepo].getRecentKeepsFromFollowedLibraries(user1.id.get, 10)
          1 === 1
        }
      }
    }

    "keeps by library without orgId" in {
      withDb() { implicit injector =>
        val orgId = Some(Id[Organization](1))
        val library = db.readWrite { implicit s =>
          val lib = libraryRepo.save(Library(name = "Kifi", ownerId = Id[User](1), visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("slug"), memberCount = 1))
          keeps(20).map(_.withLibrary(lib.id.get).withOrganizationId(orgId)).saved
          keeps(10).map(_.withLibrary(lib.id.get)).saved
          lib
        }

        val keepsWithoutOrgId = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibraryWithoutOrgId(library.id.get, None, Offset(0), Limit(50))
        }

        keepsWithoutOrgId.foreach(_.organizationId === orgId)
        keepsWithoutOrgId.length === 20
      }
    }

  }

}
