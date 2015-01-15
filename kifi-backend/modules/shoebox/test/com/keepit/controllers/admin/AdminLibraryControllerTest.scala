package com.keepit.controllers.admin

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.{ FakeUserActionsHelper, UserActionsHelper }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test._
import scala.slick.jdbc.StaticQuery.interpolation

class AdminLibraryControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule())

  "AdminLibraryController" should {
    "update library colors" in {
      withDb(modules: _*) { implicit injector =>
        val (u, ids) = db.readWrite { implicit s =>
          val u = user().saved
          val ids = libraries(3).map(_.withUser(u.id.get)).saved.map(_.id.get.id)

          sqlu"update library set color='#3975bf' where id = ${ids(0)}".first
          sqlu"update library set color='#e35957' where id = ${ids(1)}".first

          sql"select id, color from library".as[(Long, String)].list === ids.zip(Seq("#3975bf", "#e35957", null))
          (u, ids)
        }

        inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper].setUser(u, Set(ExperimentType.ADMIN))
        val request = FakeRequest("POST", "/admin/libraries/updateColors")
        status(inject[AdminLibraryController].updateLibraryColors()(request)) === NO_CONTENT

        db.readOnlyMaster { implicit s =>
          sql"select id, color from library".as[(Long, String)].list === ids.zip(Seq("#447ab7", "#dd5c60", null))
        }
      }
    }
  }
}
