package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.crypto.{ PublicIdConfiguration, TestCryptoModule }
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

  def setupUsers()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val (userIron, userCaptain, userAgent, userHulk) = db.readWrite { implicit s =>
      val userIron = userRepo.save(User(firstName = "Tony", lastName = "Stark", createdAt = t1))
      val userCaptain = userRepo.save(User(firstName = "Steve", lastName = "Rogers", createdAt = t1))
      val userAgent = userRepo.save(User(firstName = "Nick", lastName = "Fury", createdAt = t1))
      val userHulk = userRepo.save(User(firstName = "Bruce", lastName = "Banner", createdAt = t1))
      (userIron, userCaptain, userAgent, userHulk)
    }
    db.readOnlyMaster { implicit s =>
      userRepo.all.length === 4
    }
    (userIron, userCaptain, userAgent, userHulk)
  }

  def setupLibraries()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk) = setupUsers
    val t1 = new DateTime(2014, 8, 1, 1, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2014, 8, 1, 1, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
    val (libShield, libMurica, libScience) = db.readWrite { implicit s =>
      val libShield = libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
        visibility = LibraryVisibility.SECRET, ownerId = userAgent.id.get, createdAt = t1))
      val libMurica = libraryRepo.save(Library(name = "MURICA", slug = LibrarySlug("murica"),
        visibility = LibraryVisibility.ANYONE, ownerId = userAgent.id.get, createdAt = t1))
      val libScience = libraryRepo.save(Library(name = "Science & Stuff", slug = LibrarySlug("science"),
        visibility = LibraryVisibility.LIMITED, ownerId = userAgent.id.get, createdAt = t1))

      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userAgent.id.get, access = LibraryAccess.OWNER, createdAt = t2))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, createdAt = t2))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libScience.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, createdAt = t2))
      (libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.all
      allLibs.length === 3
      allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
      allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
      allLibs.map(_.description) === Seq(None, None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.ANYONE, LibraryVisibility.LIMITED)
      libraryMembershipRepo.all.length === 3
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

    val t1 = new DateTime(2014, 8, 1, 2, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Everybody loves Murica! Follow Captain America's library
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, ownerId = userCaptain.id.get, userId = userIron.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, ownerId = userCaptain.id.get, userId = userAgent.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, ownerId = userCaptain.id.get, userId = userHulk.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1))

      // Ironman invites the Hulk to contribute to 'Science & Stuff'
      libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = userHulk.id.get, access = LibraryAccess.READ_INSERT, createdAt = t1))
      (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      libraryInviteRepo.all.length === 4
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupAcceptedInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
    val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Hulk accepts Ironman's invite to see 'Science & Stuff'
      val inv1 = libraryInviteRepo.getWithLibraryIdandUserId(libraryId = libScience.id.get, userId = userHulk.id.get).get

      // Ironman & NickFury accept Captain's invite to see 'MURICA'
      val inv2 = libraryInviteRepo.getWithLibraryIdandUserId(libraryId = libMurica.id.get, userId = userIron.id.get).get
      val inv3 = libraryInviteRepo.getWithLibraryIdandUserId(libraryId = libMurica.id.get, userId = userAgent.id.get).get

      libraryMembershipRepo.save(LibraryMembership(libraryId = inv1.libraryId, userId = inv1.userId, access = inv1.access, createdAt = t1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = inv2.libraryId, userId = inv2.userId, access = inv2.access, createdAt = t1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = inv3.libraryId, userId = inv3.userId, access = inv3.access, createdAt = t1))
    }
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.all.length === 6
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  "LibraryCommander" should {
    "create libraries, memberships & invites" in {
      withDb(TestCryptoModule()) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk) = setupUsers()

        db.readOnlyMaster { implicit s =>
          libraryRepo.count === 0
        }

        val noInvites = Seq.empty[ExternalId[User]]
        val inv2: Seq[ExternalId[User]] = userIron.externalId :: userAgent.externalId :: userHulk.externalId :: Nil
        val inv3: Seq[ExternalId[User]] = userHulk.externalId :: Nil

        val lib1Request = LibraryAddRequest(name = "Avengers Missions", slug = "avengers",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites)

        val lib2Request = LibraryAddRequest(name = "MURICA", slug = "murica",
          visibility = LibraryVisibility.ANYONE, collaborators = noInvites, followers = inv2)

        val lib3Request = LibraryAddRequest(name = "Science and Stuff", slug = "science",
          visibility = LibraryVisibility.LIMITED, collaborators = inv3, followers = noInvites)

        val lib4Request = LibraryAddRequest(name = "Overlapped Invitees", slug = "overlap",
          visibility = LibraryVisibility.LIMITED, collaborators = inv2, followers = inv3)

        val lib5Request = LibraryAddRequest(name = "Invalid Param", slug = "",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites)

        val libraryCommander = inject[LibraryCommander]
        libraryCommander.addLibrary(lib1Request, userAgent.id.get).isRight === true
        libraryCommander.addLibrary(lib2Request, userCaptain.id.get).isRight === true
        libraryCommander.addLibrary(lib3Request, userIron.id.get).isRight === true
        libraryCommander.addLibrary(lib4Request, userIron.id.get).isRight === false
        libraryCommander.addLibrary(lib5Request, userIron.id.get).isRight === false

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

    "modify library" in {
      withDb(TestCryptoModule()) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val libraryCommander = inject[LibraryCommander]
        val mod1 = libraryCommander.modifyLibrary(libraryId = libShield.id.get, userId = userAgent.id.get,
          description = Some("Samuel L. Jackson was here"))
        mod1.isRight === true
        val mod2 = libraryCommander.modifyLibrary(libraryId = libMurica.id.get, userId = userCaptain.id.get,
          name = Some("MURICA #1!!!!!"), slug = Some("murica_#1"))
        mod2.isRight === true
        val mod3 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          visibility = Some(LibraryVisibility.ANYONE))
        mod3.isRight === true
        val mod4 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userHulk.id.get,
          name = Some("HULK SMASH"))
        mod4.isRight === false
        val mod5 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          name = Some(""))
        mod5.isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA #1!!!!!", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica_#1", "science")
          allLibs.map(_.description) === Seq(Some("Samuel L. Jackson was here"), None, None)
          allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.ANYONE, LibraryVisibility.ANYONE)
        }
      }
    }

    "remove library, memberships & invites" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
          libraryMembershipRepo.all.length === 6
          libraryInviteRepo.all.length === 4
        }

        val libraryCommander = inject[LibraryCommander]

        libraryCommander.removeLibrary(libMurica.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 2
          allLibs.map(_.slug.value) === Seq("avengers", "science")
          libraryMembershipRepo.all.filter(_.state == LibraryMembershipStates.ACTIVE).length === 3
          libraryInviteRepo.all.filter(_.state == LibraryInviteStates.ACTIVE).length === 1
        }

        libraryCommander.removeLibrary(libScience.id.get)
        libraryCommander.removeLibrary(libShield.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 0
          allLibs.map(_.slug.value) === Seq.empty
          libraryMembershipRepo.all.filter(_.state == LibraryMembershipStates.ACTIVE).length === 0
          libraryInviteRepo.all.filter(_.state == LibraryInviteStates.ACTIVE).length === 0
        }
      }
    }

    "get full library info by publicId" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        val libraryCommander = inject[LibraryCommander]

        val libInfo1 = libraryCommander.getLibraryByPublicId(libShield.publicId.get).right.get
        libInfo1.slug.value === "avengers"
        libInfo1.collaborators.users.length === 0
        libInfo1.followers.users.length === 0
        val libInfo2 = libraryCommander.getLibraryByPublicId(libMurica.publicId.get).right.get
        libInfo2.slug.value === "murica"
        libInfo2.collaborators.users.length === 0
        libInfo2.followers.users.length === 2
        val libInfo3 = libraryCommander.getLibraryByPublicId(libScience.publicId.get).right.get
        libInfo3.slug.value === "science"
        libInfo3.collaborators.users.length === 1
        libInfo3.followers.users.length === 0
      }
    }

    "get libraries by user (which libs am I following / contributing to?)" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites

        db.readOnlyMaster { implicit s =>
          val libraryCommander = inject[LibraryCommander]
          val targetLib1 = libraryCommander.getLibrariesByUser(userIron.id.get)
          targetLib1.isRight === true
          val targetLib2 = libraryCommander.getLibrariesByUser(userCaptain.id.get)
          targetLib2.isRight === true
          val targetLib3 = libraryCommander.getLibrariesByUser(userAgent.id.get)
          targetLib3.isRight === true
          val targetLib4 = libraryCommander.getLibrariesByUser(userHulk.id.get)
          targetLib4.isRight === true

          val (ironLibs, ironAccesses) = targetLib1.right.get.unzip
          ironLibs.map(_.slug.value) === Seq("science", "murica")
          ironAccesses === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (captainLibs, captainAccesses) = targetLib2.right.get.unzip
          captainLibs.map(_.slug.value) === Seq("murica")
          captainAccesses === Seq(LibraryAccess.OWNER)

          val (agentLibs, agentAccesses) = targetLib3.right.get.unzip
          agentLibs.map(_.slug.value) === Seq("avengers", "murica")
          agentAccesses === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (hulkLibs, hulkAccesses) = targetLib4.right.get.unzip
          hulkLibs.map(_.slug.value) === Seq("science")
          hulkAccesses === Seq(LibraryAccess.READ_INSERT)
        }
      }
    }
  }
}
