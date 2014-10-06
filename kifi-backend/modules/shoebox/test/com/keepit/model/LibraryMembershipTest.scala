package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryMembershipTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = userRepo.save(User(firstName = "Aaron", lastName = "H", createdAt = t1))
      val user2 = userRepo.save(User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2)))
      val library1 = libraryRepo.save(Library(name = "Lib1", ownerId = user1.id.get, createdAt = t1.plusMinutes(2),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("A"), memberCount = 1))
      val library2 = libraryRepo.save(Library(name = "Lib2", ownerId = user2.id.get, createdAt = t1.plusMinutes(5),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("B"), memberCount = 1))
      val lm1 = libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get,
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1), showInSearch = true))
      val lm2 = libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user2.id.get,
        access = LibraryAccess.READ_ONLY, createdAt = t1.plusHours(2), showInSearch = true))
      val lm3 = libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get,
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(3), showInSearch = true))
      val lm4 = libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user1.id.get,
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(4), showInSearch = true))
      (library1, library2, user1, user2, lm1, lm2, lm3, lm4, t1)
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

    "getNotViewdOrEmailed" in { // test read/write/save
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, lm1, lm2, lm3, lm4, t1) = setup()

        val user1libs = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user1.id.get, t1))
        user1libs.size === 2
        val user2libs = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user2.id.get, t1))
        user2libs.size === 2

        db.readWrite(implicit s => libraryMembershipRepo.save(lm1.copy(lastViewed = Some(t1.plusDays(1)))))
        val user1libsAfterView = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user1.id.get, t1))
        user1libsAfterView.size === 1
        user1libsAfterView.head === lm4.libraryId
        val user1libsAfterView2 = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user1.id.get, t1.plusDays(2)))
        user1libsAfterView2.size === 2

        //======= user 2 ===============
        val user2libs2 = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user2.id.get, t1))
        user2libs2.size === 2

        db.readWrite(implicit s => libraryMembershipRepo.save(lm2.copy(lastEmailSent = Some(t1.plusDays(1)))))
        val user2libsAfterView = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user2.id.get, t1))
        user2libsAfterView.size === 1
        user2libsAfterView.head === lm3.libraryId
        val user2libsAfterView2 = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user2.id.get, t1.plusDays(2)))
        user2libsAfterView2.size === 2
        db.readWrite(implicit s => libraryMembershipRepo.save(lm3.copy(lastViewed = Some(t1.plusDays(1)))))
        val user2libsAfterView3 = db.readOnlyMaster(implicit s => libraryMembershipRepo.getNotViewdOrEmailed(user2.id.get, t1))
        user2libsAfterView3.size === 0

      }
    }

    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, lm1, lm2, lm3, lm4, t1) = setup()
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
            access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1), showInSearch = true))
        }
        db.readWrite { implicit s =>
          libraryMembershipRepo.count === 4
        }
      }
    }

  }
}
