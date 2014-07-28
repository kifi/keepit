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
      userRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk)
  }

  def setupLibraries()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk) = setupUsers
    val t1 = new DateTime(2014, 8, 1, 1, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2014, 8, 1, 1, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
    val (libShield, libMurica, libScience) = db.readWrite { implicit s =>
      val libShield = libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
        visibility = LibraryVisibility.SECRET, ownerId = userAgent.id.get, createdAt = t1, keepDiscoveryEnabled = false))
      val libMurica = libraryRepo.save(Library(name = "MURICA", slug = LibrarySlug("murica"),
        visibility = LibraryVisibility.ANYONE, ownerId = userCaptain.id.get, createdAt = t1, keepDiscoveryEnabled = true))
      val libScience = libraryRepo.save(Library(name = "Science & Stuff", slug = LibrarySlug("science"),
        visibility = LibraryVisibility.LIMITED, ownerId = userIron.id.get, createdAt = t1, keepDiscoveryEnabled = true))

      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userAgent.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libScience.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      (libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.all
      allLibs.length === 3
      allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
      allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
      allLibs.map(_.description) === Seq(None, None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.ANYONE, LibraryVisibility.LIMITED)
      libraryMembershipRepo.count === 3
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
      libraryInviteRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupAcceptedInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
    val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Hulk accepts Ironman's invite to see 'Science & Stuff'
      val inv1 = libraryInviteRepo.getWithLibraryIdandUserId(libraryId = libScience.id.get, userId = userHulk.id.get).head
      libraryInviteRepo.save(inv1.withState(LibraryInviteStates.ACCEPTED))

      // Ironman & NickFury accept Captain's invite to see 'MURICA'
      val inv2 = libraryInviteRepo.getWithLibraryIdandUserId(libraryId = libMurica.id.get, userId = userIron.id.get).head
      libraryInviteRepo.save(inv2.withState(LibraryInviteStates.ACCEPTED))
      val inv3 = libraryInviteRepo.getWithLibraryIdandUserId(libraryId = libMurica.id.get, userId = userAgent.id.get).head
      libraryInviteRepo.save(inv3.withState(LibraryInviteStates.ACCEPTED))

      libraryMembershipRepo.save(LibraryMembership(libraryId = inv1.libraryId, userId = inv1.userId, access = inv1.access, showInSearch = true, createdAt = t1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = inv2.libraryId, userId = inv2.userId, access = inv2.access, showInSearch = true, createdAt = t1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = inv3.libraryId, userId = inv3.userId, access = inv3.access, showInSearch = true, createdAt = t1))
    }
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.count === 6
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupKeeps()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
    val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val site1 = "http://www.reddit.com/r/murica"
    val site2 = "http://www.freedom.org/"
    val site3 = "http://www.mcdonalds.com/"

    db.readWrite { implicit s =>
      val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
      val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
      val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

      val keep1 = keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), libraryId = Some(libMurica.id.get)))
      val keep2 = keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), libraryId = Some(libMurica.id.get)))
      val keep3 = keepRepo.save(Keep(title = Some("McDonalds"), userId = userCaptain.id.get, url = url3.url, urlId = url3.id.get,
        uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), libraryId = Some(libMurica.id.get)))
    }
    db.readOnlyMaster { implicit s =>
      keepRepo.count === 3
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
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites, keepDiscoveryEnabled = true)

        val lib2Request = LibraryAddRequest(name = "MURICA", slug = "murica",
          visibility = LibraryVisibility.ANYONE, collaborators = noInvites, followers = inv2, keepDiscoveryEnabled = true)

        val lib3Request = LibraryAddRequest(name = "Science and Stuff", slug = "science",
          visibility = LibraryVisibility.LIMITED, collaborators = inv3, followers = noInvites, keepDiscoveryEnabled = true)

        val lib4Request = LibraryAddRequest(name = "Overlapped Invitees", slug = "overlap",
          visibility = LibraryVisibility.LIMITED, collaborators = inv2, followers = inv3, keepDiscoveryEnabled = true)

        val lib5Request = LibraryAddRequest(name = "Invalid Param", slug = "",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites, keepDiscoveryEnabled = true)

        val libraryCommander = inject[LibraryCommander]
        val add1 = libraryCommander.addLibrary(lib1Request, userAgent.id.get)
        add1.isRight === true
        add1.right.get.name === "Avengers Missions"
        val add2 = libraryCommander.addLibrary(lib2Request, userCaptain.id.get)
        add2.isRight === true
        add2.right.get.name === "MURICA"
        val add3 = libraryCommander.addLibrary(lib3Request, userIron.id.get)
        add3.isRight === true
        add3.right.get.name === "Science and Stuff"
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
        mod1.right.get.description === Some("Samuel L. Jackson was here")

        val mod2 = libraryCommander.modifyLibrary(libraryId = libMurica.id.get, userId = userCaptain.id.get,
          name = Some("MURICA #1!!!!!"), slug = Some("murica_#1"))
        mod2.isRight === true
        mod2.right.get.name === "MURICA #1!!!!!"
        mod2.right.get.slug === LibrarySlug("murica_#1")

        val mod3 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          visibility = Some(LibraryVisibility.ANYONE))
        mod3.isRight === true
        mod3.right.get.visibility === LibraryVisibility.ANYONE

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
          libraryMembershipRepo.count === 6
          libraryInviteRepo.count === 4
        }

        val libraryCommander = inject[LibraryCommander]

        libraryCommander.removeLibrary(libMurica.id.get, userCaptain.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 2
          allLibs.map(_.slug.value) === Seq("avengers", "science")
          libraryMembershipRepo.all.filter(_.state == LibraryMembershipStates.INACTIVE).length === 3
          libraryInviteRepo.all.filter(_.state == LibraryInviteStates.INACTIVE).length === 3
        }

        libraryCommander.removeLibrary(libScience.id.get, userIron.id.get)
        libraryCommander.removeLibrary(libShield.id.get, userAgent.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 0
          allLibs.map(_.slug.value) === Seq.empty
          libraryMembershipRepo.all.filter(_.state == LibraryMembershipStates.INACTIVE).length === 6
          libraryInviteRepo.all.filter(_.state == LibraryInviteStates.INACTIVE).length === 4
        }
      }
    }

    "get library by publicId" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        val libraryCommander = inject[LibraryCommander]

        val lib1 = libraryCommander.getLibraryById(libShield.id.get)
        lib1.id === libShield.id
        val lib2 = libraryCommander.getLibraryById(libMurica.id.get)
        lib2.id === libMurica.id
        val lib3 = libraryCommander.getLibraryById(libScience.id.get)
        lib3.id === libScience.id
      }
    }

    "get libraries by user (which libs am I following / contributing to?)" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites

        db.readOnlyMaster { implicit s =>
          val libraryCommander = inject[LibraryCommander]
          val targetLib1 = libraryCommander.getLibrariesByUser(userIron.id.get)
          val targetLib2 = libraryCommander.getLibrariesByUser(userCaptain.id.get)
          val targetLib3 = libraryCommander.getLibrariesByUser(userAgent.id.get)
          val targetLib4 = libraryCommander.getLibrariesByUser(userHulk.id.get)

          val (ironAccesses, ironLibs) = targetLib1.unzip
          ironLibs.map(_.slug.value) === Seq("science", "murica")
          ironAccesses === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (captainAccesses, captainLibs) = targetLib2.unzip
          captainLibs.map(_.slug.value) === Seq("murica")
          captainAccesses === Seq(LibraryAccess.OWNER)

          val (agentAccesses, agentLibs) = targetLib3.unzip
          agentLibs.map(_.slug.value) === Seq("avengers", "murica")
          agentAccesses === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (hulkAccesses, hulkLibs) = targetLib4.unzip
          hulkLibs.map(_.slug.value) === Seq("science")
          hulkAccesses === Seq(LibraryAccess.READ_INSERT)
        }
      }
    }

    "intern user system libraries" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]

        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        db.readOnlyMaster { implicit session =>
          val all = libraryRepo.all()
          all.size === 3

          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 1
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 1
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)
        libraryCommander.internSystemGeneratedLibraries(userCaptain.id.get)

        // System libraries are created
        db.readOnlyMaster { implicit session =>
          libraryRepo.all().size === 7
          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 3
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 3
          libraryRepo.getByUser(userHulk.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 0
        }

        // Operation is idempotent
        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)
        libraryCommander.internSystemGeneratedLibraries(userHulk.id.get)
        db.readWrite { implicit session =>
          libraryRepo.all().size === 9
          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 3
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 3
          libraryRepo.getByUser(userHulk.id.get).map(_._2).count(_.ownerId == userHulk.id.get) === 2

          val ironSysLibs = libraryRepo.getByUser(userIron.id.get).map(_._2).filter(_.ownerId == userIron.id.get)
          val main = ironSysLibs.find(_.kind == LibraryKind.SYSTEM_MAIN).get
          val secret = ironSysLibs.find(_.kind == LibraryKind.SYSTEM_SECRET).get

          main.state === LibraryStates.ACTIVE
          main.name === "Main Library"
          main.slug === LibrarySlug("main")
          main.id.get === Id[Library](4)
          secret.state === LibraryStates.ACTIVE

          libraryRepo.save(main.copy(state = LibraryStates.INACTIVE, visibility = LibraryVisibility.LIMITED, slug = LibrarySlug("main_old")))
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        // Fixes problems in sys libraries
        db.readOnlyMaster { implicit session =>
          val ironMain = libraryRepo.getByUser(userIron.id.get).map(_._2).filter(_.ownerId == userIron.id.get).find(_.kind == LibraryKind.SYSTEM_MAIN).get

          ironMain.state === LibraryStates.ACTIVE
          ironMain.visibility === LibraryVisibility.SECRET
        }

        // Removes dupes
        db.readWrite { implicit session =>
          val lib = libraryRepo.save(Library(ownerId = userIron.id.get, name = "Main 2!", kind = LibraryKind.SYSTEM_MAIN, visibility = LibraryVisibility.LIMITED, slug = LibrarySlug("main2"), keepDiscoveryEnabled = true))
          libraryMembershipRepo.save(LibraryMembership(userId = userIron.id.get, libraryId = lib.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          println(libraryRepo.all.mkString("\n"))
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        db.readOnlyMaster { implicit session =>
          val ironMains = libraryRepo.getByUser(userIron.id.get, None).map(_._2).filter(_.ownerId == userIron.id.get).filter(_.kind == LibraryKind.SYSTEM_MAIN)
          ironMains.size === 1
          ironMains.count(l => l.state == LibraryStates.ACTIVE) === 1
          libraryRepo.all.count(l => l.ownerId == userIron.id.get && l.state == LibraryStates.INACTIVE) === 1
        }

      }
    }

    "invite users" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries
        val libraryCommander = inject[LibraryCommander]

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 0
        }

        val inviteList1 = Seq(
          (userIron.id.get, LibraryAccess.READ_ONLY),
          (userAgent.id.get, LibraryAccess.READ_ONLY),
          (userHulk.id.get, LibraryAccess.READ_ONLY))
        val res1 = libraryCommander.inviteUsersToLibrary(libMurica.id.get, userCaptain.id.get, inviteList1)
        res1.isRight === true
        res1.right.get === Seq((userIron.externalId, LibraryAccess.READ_ONLY),
          (userAgent.externalId, LibraryAccess.READ_ONLY),
          (userHulk.externalId, LibraryAccess.READ_ONLY))

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 3
          libraryInviteRepo.all.map(x => (x.userId, x.access)) ===
            Seq((userIron.id.get, LibraryAccess.READ_ONLY),
              (userAgent.id.get, LibraryAccess.READ_ONLY),
              (userHulk.id.get, LibraryAccess.READ_ONLY))
        }

        // Scumbag Ironman tries to invite himself for READ_WRITE access
        val inviteList2 = Seq((userIron.id.get, LibraryAccess.READ_WRITE))
        val res2 = libraryCommander.inviteUsersToLibrary(libMurica.id.get, userIron.id.get, inviteList2)
        res2.isRight === false

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 3
        }
      }
    }

    "let users join or decline library invites" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
        val libraryCommander = inject[LibraryCommander]

        val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = userHulk.id.get, access = LibraryAccess.READ_WRITE, createdAt = t1))
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = userHulk.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1))
        }

        val inviteIds = db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 6
          libraryInviteRepo.all.map(_.id.get)
        }
        libraryCommander.joinLibrary(inviteIds(0)) === libMurica // Ironman accepts invite to 'Murica'
        libraryCommander.joinLibrary(inviteIds(1)) === libMurica // Agent accepts invite to 'Murica'
        libraryCommander.declineLibrary(inviteIds(2)) // Hulk declines invite to 'Murica'
        libraryCommander.joinLibrary(inviteIds(3)) === libScience // Hulk accepts invite to 'Science' (READ_INSERT) but gets READ_WRITE access

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 6
          val res = for (inv <- libraryInviteRepo.all) yield {
            (inv.libraryId, inv.userId, inv.access, inv.state)
          }
          res === Seq(
            (libMurica.id.get, userIron.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED),
            (libMurica.id.get, userAgent.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED),
            (libMurica.id.get, userHulk.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.DECLINED),
            (libScience.id.get, userHulk.id.get, LibraryAccess.READ_INSERT, LibraryInviteStates.ACCEPTED),
            (libScience.id.get, userHulk.id.get, LibraryAccess.READ_WRITE, LibraryInviteStates.ACCEPTED),
            (libScience.id.get, userHulk.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED)
          )
        }
      }
    }

    "let users leave library" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        val libraryCommander = inject[LibraryCommander]

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.count === 6
          libraryMembershipRepo.all.count(x => x.state == LibraryMembershipStates.INACTIVE) === 0
        }

        libraryCommander.leaveLibrary(libMurica.id.get, userAgent.id.get).isRight === true

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.count === 6
          libraryMembershipRepo.all.count(x => x.state == LibraryMembershipStates.INACTIVE) === 1
        }
      }
    }

    "get keeps" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        val libraryCommander = inject[LibraryCommander]

        libraryCommander.getKeeps(libShield.id.get).length === 0
        libraryCommander.getKeeps(libScience.id.get).length === 0
        val res = libraryCommander.getKeeps(libMurica.id.get)
        res.length === 3
        res.map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
      }
    }

    "copy keeps to another library" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        val libraryCommander = inject[LibraryCommander]

        // Ironman has read-only to Murica, and read-write to Freedom, and owns 2 libraries: IronMurica & Science
        val (libFreedom, libIronMurica, keepsInMurica) = db.readWrite { implicit s =>
          val libFreedom = libraryRepo.save(Library(name = "Freedom", slug = LibrarySlug("freedom"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.ANYONE, isSearchableByOthers = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libFreedom.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libFreedom.id.get, userId = userIron.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))

          val libIronMurica = libraryRepo.save(Library(name = "IronMurica", slug = LibrarySlug("ironmurica"), ownerId = userIron.id.get, visibility = LibraryVisibility.ANYONE, isSearchableByOthers = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libIronMurica.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val keepsInMurica = keepRepo.getByLibrary(libMurica.id.get).toSet
          (libFreedom, libIronMurica, keepsInMurica)
        }

        // Ironman copies 3 keeps from Murica to IronMurica (tests RO -> RW)
        val copy1 = libraryCommander.copyKeeps(userIron.id.get, libIronMurica.id.get, keepsInMurica)
        copy1._1.slug === LibrarySlug("ironmurica")
        copy1._2.size === 0

        val keepsInIronMurica = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libMurica.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libIronMurica.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libIronMurica.id.get).toSet
        }

        // Ironman attempts to copy from IronMurica to Murica (but only has read_only access to Murica) (tests RW -> RO)
        val copy2 = libraryCommander.copyKeeps(userIron.id.get, libMurica.id.get, keepsInIronMurica.slice(0, 2))
        copy2._1.slug === LibrarySlug("murica")
        copy2._2.size === 2
        // Ironman copies 2 keeps from IronMurica to Freedom (tests RW -> RW)
        val copy3 = libraryCommander.copyKeeps(userIron.id.get, libFreedom.id.get, keepsInIronMurica.slice(0, 2))
        copy3._1.slug === LibrarySlug("freedom")
        copy3._2.size === 0

        db.readOnlyMaster { implicit s =>
          keepRepo.count === 8
          keepRepo.getByLibrary(libMurica.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libIronMurica.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libFreedom.id.get).map(_.title.get) === Seq("Reddit", "Freedom")
        }

        // Ironman copies duplicates from Murica to Freedom
        val copy4 = libraryCommander.copyKeeps(userIron.id.get, libFreedom.id.get, keepsInMurica)
        copy4._1.slug === LibrarySlug("freedom")
        copy4._2.size === 2

        db.readOnlyMaster { implicit s =>
          keepRepo.count === 9
          keepRepo.getByLibrary(libFreedom.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
        }
      }
    }

    "move keeps to another library" in {
      withDb(TestCryptoModule()) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        val libraryCommander = inject[LibraryCommander]

        // Ironman has read-only to Murica, and read-write to Freedom, and owns 2 libraries: IronMurica & Science
        val (libFreedom, libIronMurica, keepsInMurica) = db.readWrite { implicit s =>
          val libFreedom = libraryRepo.save(Library(name = "Freedom", slug = LibrarySlug("freedom"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.ANYONE, isSearchableByOthers = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libFreedom.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libFreedom.id.get, userId = userIron.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))

          val libIronMurica = libraryRepo.save(Library(name = "IronMurica", slug = LibrarySlug("ironmurica"), ownerId = userIron.id.get, visibility = LibraryVisibility.ANYONE, isSearchableByOthers = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libIronMurica.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val keepsInMurica = keepRepo.getByLibrary(libMurica.id.get).toSet
          (libFreedom, libIronMurica, keepsInMurica)
        }

        // Ironman attempts to move keeps from Murica to IronMurica (tests RO -> RW)
        val move1 = libraryCommander.moveKeeps(userIron.id.get, libIronMurica.id.get, keepsInMurica)
        move1._1.slug === LibrarySlug("ironmurica")
        move1._2.size === 3

        libraryCommander.copyKeeps(userIron.id.get, libIronMurica.id.get, keepsInMurica)
        val keepsInMyMurica = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libIronMurica.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libIronMurica.id.get).toSet
        }

        // Ironman moves 2 keeps from IronMurica to Freedom (tests RW -> RW)
        val move2 = libraryCommander.moveKeeps(userIron.id.get, libFreedom.id.get, keepsInMyMurica.slice(0, 2))
        move2._1.slug === LibrarySlug("freedom")
        move2._2.size === 0

        val keepsInFreedom = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libIronMurica.id.get).map(_.title.get) === Seq("McDonalds")
          keepRepo.getByLibrary(libFreedom.id.get).map(_.title.get) === Seq("Reddit", "Freedom")
          keepRepo.getByLibrary(libFreedom.id.get).toSet
        }

        // Ironman attempts to move keeps from IronMurica to Murica (tests RW -> RO)
        val move3 = libraryCommander.moveKeeps(userIron.id.get, libMurica.id.get, keepsInFreedom)
        move3._1.slug === LibrarySlug("murica")
        move3._2.size === 2

        libraryCommander.copyKeeps(userIron.id.get, libIronMurica.id.get, keepsInMurica)
        val keepsInMyMurica2 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 8
          keepRepo.getByLibrary(libIronMurica.id.get).map(_.title.get) === Seq("McDonalds", "Reddit", "Freedom")
          keepRepo.getByLibrary(libIronMurica.id.get).toSet
        }

        // move duplicates (Reddit) IronMurica -> Freedom
        val move6 = libraryCommander.moveKeeps(userIron.id.get, libFreedom.id.get, keepsInMyMurica2.slice(0, 2))
        move6._1.slug === LibrarySlug("freedom")
        move6._2.size === 1

        db.readOnlyMaster { implicit s =>
          keepRepo.count === 8
          keepRepo.getByLibrary(libIronMurica.id.get).map(_.title.get) === Seq("Reddit", "Freedom") // for now, URIs that exist in toLibrary also stay in fromLibrary
          keepRepo.getByLibrary(libFreedom.id.get).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
        }
      }
    }
  }
}
