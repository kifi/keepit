package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryCommanderTest extends Specification with ShoeboxTestInjector {

  def modules = FakeScrapeSchedulerModule() :: FakeSearchServiceClientModule() :: Nil

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      val userIron = userRepo.save(User(firstName = "Tony", lastName = "Stark", createdAt = t1))
      val userCaptain = userRepo.save(User(firstName = "Steve", lastName = "Rogers", createdAt = t1))
      val userAgent = userRepo.save(User(firstName = "Nick", lastName = "Fury", createdAt = t1))
      val userHulk = userRepo.save(User(firstName = "Bruce", lastName = "Banner", createdAt = t1))
      (userIron, userCaptain, userAgent, userHulk)
    }
  }

  "LibraryCommander" should {
    "create libraries, memberships & invites" in {
      withDb() { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk) = setup()
        val t1 = new DateTime(2014, 7, 11, 1, 10, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2014, 7, 12, 2, 20, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t3 = new DateTime(2014, 7, 13, 3, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        db.readOnlyMaster { implicit s =>
          libraryRepo.count === 0
        }

        val noInvites = Seq.empty[ExternalId[User]]
        val inv2: Seq[ExternalId[User]] = userIron.externalId :: userAgent.externalId :: userHulk.externalId :: Nil
        val inv3: Seq[ExternalId[User]] = userHulk.externalId :: Nil

        val lib1Request = LibraryAddRequest(name = "Avengers Missions", slug = "avengers",
          visibility = LibraryVisibility.SECRET.value, collaborators = noInvites, followers = noInvites)

        val lib2Request = LibraryAddRequest(name = "MURICA", slug = "murica",
          visibility = LibraryVisibility.ANYONE.value, collaborators = noInvites, followers = inv2)

        val lib3Request = LibraryAddRequest(name = "Science and Stuff", slug = "science",
          visibility = LibraryVisibility.LIMITED.value, collaborators = inv3, followers = noInvites)

        val lib4Request = LibraryAddRequest(name = "Overlapped Invitees", slug = "overlap",
          visibility = LibraryVisibility.LIMITED.value, collaborators = inv2, followers = inv3)

        val add1 = inject[LibraryCommander].addLibrary(lib1Request, userAgent.id.get)
        add1.isRight === true
        val add2 = inject[LibraryCommander].addLibrary(lib2Request, userCaptain.id.get)
        add2.isRight === true
        val add3 = inject[LibraryCommander].addLibrary(lib3Request, userIron.id.get)
        add3.isRight === true
        val add4 = inject[LibraryCommander].addLibrary(lib4Request, userIron.id.get)
        add4.isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")

          val allMemberships = libraryMembershipRepo.all
          allMemberships.length === 3
          allMemberships.map(_.userId) === Seq(userAgent.id.get, userCaptain.id.get, userIron.id.get)
          allMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.OWNER, LibraryAccess.OWNER)

          val allInvites = libraryInviteRepo.all
          allInvites.length === 4
          val invitePairs = for (i <- allInvites) yield (i.ownerId, i.userId)

          invitePairs === (userCaptain.id.get, userIron.id.get) ::
            (userCaptain.id.get, userAgent.id.get) ::
            (userCaptain.id.get, userHulk.id.get) ::
            (userIron.id.get, userHulk.id.get) ::
            Nil
        }
      }

    }
  }
}
