package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._

class KeepToLibraryRepoTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeSocialGraphModule()
  )
  def createUri(title: String, url: String)(implicit session: RWSession, injector: Injector) = {
    uriRepo.save(NormalizedURI.withHash(title = Some(title), normalizedUrl = url))
  }

  "KeepToLibraryRepo" should {
    "find keeps by uri" in {
      withDb(modules: _*) { implicit injector =>
        val (user, uri, libs, keeps) = db.readWrite { implicit rw =>
          val user = UserFactory.user().saved
          val libs = LibraryFactory.libraries(10).map(_.withUser(user)).saved
          val uri = createUri("Google", "google.com")
          val keeps = for (lib <- libs) yield {
            KeepFactory.keep().withURIId(uri.id.get).withLibrary(lib).saved
          }
          (user, uri, libs, keeps)
        }
        libs.length === 10
        keeps.length === 10
      }
    }
  }
}
