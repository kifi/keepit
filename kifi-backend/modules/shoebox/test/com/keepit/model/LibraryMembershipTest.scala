package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryMembershipTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val u1 = new User(firstName = "Aaron", lastName = "H", createdAt = t1)
    val u2 = new User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2))

    db.readWrite { implicit s =>
      val user1 = userRepo.save(u1)
      val user2 = userRepo.save(u2)
      val library1 = libraryRepo.save(Library(name = "Lib1", ownerId = user1.id.get, createdAt = t1.plusMinutes(2),
        visibility = LibraryVisibility.ANYONE, slug = LibrarySlug("A")))
      val library2 = libraryRepo.save(Library(name = "Lib2", ownerId = user2.id.get, createdAt = t1.plusMinutes(5),
        visibility = LibraryVisibility.ANYONE, slug = LibrarySlug("B")))
      val lm1 = libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get,
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
      val lm2 = libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user2.id.get,
        access = LibraryAccess.READ_ONLY, createdAt = t1.plusHours(2)))
      val lm3 = libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get,
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(3)))
      val lm4 = libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user1.id.get,
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(4)))
      (library1, library2, user1, user2, lm1, lm2, lm3, lm4)
    }
  }

  "LibraryMembershipRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        setup()
        val all = db.readOnlyMaster(implicit session => libraryMembershipRepo.all)
        all.map(_.access) === Seq(LibraryAccess.READ_WRITE, LibraryAccess.READ_ONLY, LibraryAccess.READ_WRITE, LibraryAccess.READ_WRITE)
      }
    }

    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, lm1, lm2, lm3, lm4) = setup()
        db.readWrite { implicit s =>
          libraryMembershipRepo.count === 4
          val libMem = libraryMembershipRepo.get(lm1.id.get)
          libraryMembershipRepo.delete(libMem)
        }
        db.readWrite { implicit s =>
          libraryMembershipRepo.all.size === 3
          libraryMembershipRepo.count === 3
        }
        db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get,
            access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
        }
        db.readWrite { implicit s =>
          libraryMembershipRepo.count === 4
        }
      }
    }

  }
}
