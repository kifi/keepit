package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.shoebox.data.keep.KeepRecipientId
import com.keepit.test._
import org.joda.time.DateTime
import org.specs2.mutable._
import com.keepit.model.FeedFilter._

class KeepRepoTest extends Specification with ShoeboxTestInjector {

  "KeepRepo" should {
    "save and load a keep" in {
      withDb() { implicit injector =>
        db.readWrite { implicit session =>
          val savedKeep = keepRepo.save(Keep(
            uriId = Id[NormalizedURI](1),
            url = "http://www.kifi.com",
            userId = Some(Id[User](3)),
            originalKeeperId = Some(Id[User](3)),
            source = KeepSource.Keeper,
            recipients = KeepRecipients(libraries = Set(Id(4)), users = Set(Id(3)), emails = Set(EmailAddress("ryan@kifi.com")))
          ))
          val dbKeep = keepRepo.getNoCache(savedKeep.id.get)
          val cacheKeep = keepRepo.get(savedKeep.id.get)

          // The savedKeep is not equal to the dbKeep because of originalKeeperId
          def f(k: Keep) = (k.id.get, k.uriId, k.url, k.userId, k.source, k.recipients)
          f(dbKeep) === f(savedKeep)
          f(dbKeep) === f(cacheKeep)
        }
        1 === 1
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
    "get keeps (organized into sections) on a page" in {
      withDb() { implicit injector =>
        // Just to ensure that the syntax is valid
        db.readOnlyMaster { implicit s =>
          (ktuRepo.count, ktlRepo.count, libraryMembershipRepo.count, orgMembershipRepo.count)
          keepRepo.getSectionedKeepsOnUri(Id(1), Id(1), Set.empty, limit = 10, None)
          keepRepo.getSectionedKeepsOnUri(Id(1), Id(1), Set(Id(42)), limit = 10, None)
          keepRepo.getSectionedKeepsOnUri(Id(1), Id(1), Set(Id(42)), limit = 10, Some(KeepRecipientId.UserId(Id(1))))
        }
        1 === 1
      }
    }
  }
}
