package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.commanders.KeepQuery.Arrangement.FromOrdering
import com.keepit.commanders.KeepQuery.{ ForUri, ForLibrary }
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.common.util.Ord
import com.keepit.model.KeepFactoryHelper.KeepPersister
import com.keepit.model.KeepOrdering.KEPT_AT
import com.keepit.model.LibraryFactoryHelper.LibraryPersister
import com.keepit.model.UserFactoryHelper.UserPersister
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.util.Random

class KeepQueryCommanderTest extends Specification with ShoeboxTestInjector {
  def rnd(lo: Int, hi: Int): Int = lo + Random.nextInt(hi - lo)
  implicit def pubIdConfig(implicit injector: Injector): PublicIdConfiguration = inject[PublicIdConfiguration]
  val modules = Seq(
    FakeUserActionsModule()
  )
  "KeepQueryCommander" should {
    "allow user-specified ordering" in {
      withDb(modules: _*) { implicit injector =>
        val now = fakeClock.now
        val (user, lib, keeps) = db.readWrite { implicit s =>
          val user = UserFactory.user().saved
          val lib = LibraryFactory.library().withOwner(user).saved
          val keeps = KeepFactory.keeps(20).map { k =>
            k.withLibrary(lib).withUser(user)
              .withKeptAt(now minusHours rnd(500, 1000))
              .withLastActivityAt(now minusHours rnd(50, 200))
              .saved
          }
          (user, lib, keeps)
        }

        val byLAA = keeps.sortBy(k => (k.lastActivityAt.getMillis, k.id.get.id))(Ord.descending).map(_.id.get)
        val byKA = keeps.sortBy(k => (k.keptAt.getMillis, k.id.get.id))(Ord.descending).map(_.id.get)

        val query = KeepQuery(
          target = ForLibrary(lib.id.get),
          arrangement = None,
          paging = KeepQuery.Paging(fromId = None, offset = Offset(0), limit = Limit(10))
        )
        db.readOnlyMaster { implicit s =>
          // By default, order by lastActivityAt
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query) === byLAA.take(10)
          // Respect the limit
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.withLimit(1)) === byLAA.take(1)
          // order by something else
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.withArrangement(KEPT_AT.desc)) === byKA.take(10)
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.withArrangement(KEPT_AT.asc)) === byKA.reverse.take(10)

          // Page by id
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.fromId(byLAA(5))) === byLAA.drop(6).take(10)
        }
        1 === 1
      }
    }
    "get keeps on a uri with at least the specified set of recipients" in {
      withDb(modules: _*) { implicit injector =>
        val uri = Id[NormalizedURI](42)
        val (user, lib) = db.readWrite { implicit s =>
          val user = UserFactory.user().saved
          val lib = LibraryFactory.library().withOwner(user).saved
          KeepFactory.keep().withURIId(uri).withLibrary(lib).withUser(user).saved
          KeepFactory.keep().withURIId(uri).withLibrary(lib).saved
          KeepFactory.keep().withURIId(uri).withUser(user).saved
          KeepFactory.keep().withURIId(uri).saved
          (user, lib)
        }

        val query = KeepQuery(
          target = ForUri(uri, KeepRecipients.EMPTY),
          arrangement = None,
          paging = KeepQuery.Paging(fromId = None, offset = Offset(0), limit = Limit(10))
        )
        db.readOnlyMaster { implicit s =>
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query).length === 4
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.copy(target = ForUri(uri, KeepRecipients.EMPTY.plusUser(user.id.get)))).length === 2
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.copy(target = ForUri(uri, KeepRecipients.EMPTY.plusLibrary(lib.id.get)))).length === 2
          inject[KeepQueryCommander].getKeeps(Some(user.id.get), query.copy(target = ForUri(uri, KeepRecipients.EMPTY.plusUser(user.id.get).plusLibrary(lib.id.get)))).length === 1
        }
        1 === 1
      }
    }
  }
}
