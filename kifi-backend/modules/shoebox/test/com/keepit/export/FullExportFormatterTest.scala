package com.keepit.export

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.json.EitherFormat
import com.keepit.common.util.{ EnglishProperNouns, TimedComputation }
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.rover.model.RoverUriSummary
import com.keepit.social.BasicUser
import com.keepit.test.{ ShoeboxInjectionHelpers, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.iteratee.{ Enumeratee, Enumerator, Iteratee }
import play.api.libs.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

class FullExportFormatterTest extends Specification with ShoeboxTestInjector with ShoeboxInjectionHelpers {
  def modules = Seq(
    FakeExecutionContextModule()
  )

  "FullExportFormatter" should {
    def setup()(implicit injector: Injector): (User, Organization, Seq[Library], Seq[Keep]) = db.readWrite { implicit session =>
      val owner = UserFactory.user().withName("Ryan", "Brewster").saved
      val rando = UserFactory.user().withName("Rando", "McRanderson").saved
      val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(rando)).withName("Brewstercorp").saved
      val libs = {
        val libNames = EnglishProperNouns.planets.toIterator
        Seq(
          LibraryFactory.library().withName(libNames.next()).withOwner(owner).withCollaborators(Seq(rando)).withOrganization(org).withVisibility(LibraryVisibility.PUBLISHED).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(owner).withCollaborators(Seq(rando)).withOrganization(org).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(owner).withCollaborators(Seq(rando)).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(rando).withCollaborators(Seq(owner)).withOrganization(org).withVisibility(LibraryVisibility.SECRET).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(rando).withCollaborators(Seq(owner)).withOrganization(org).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(rando).withCollaborators(Seq(owner)).saved,
          libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(org.id.get), LibraryKind.SYSTEM_ORG_GENERAL).head
        )
      }
      LibraryFactory.library().withOwner(rando).saved
      val keeps = libs.flatMap { lib =>
        Seq(
          KeepFactory.keep().withUser(owner).withLibrary(lib).saved,
          KeepFactory.keep().withUser(rando).withLibrary(lib).saved
        )
      }
      (owner, org, libs, keeps)
    }
    "produce a giant javascript file" in {
      withDb(modules: _*) { implicit injector =>
        val (user, org, libs, keeps) = setup()
        val stream = inject[FullExportProducer].fullExport(user.id.get)
        val enum = inject[FullExportFormatter].assignments(stream)
        Await.result(enum.run(Iteratee.foreach {
          case (e, v) =>
            // Uncomment to view the expected output
            // println(s"$e = ${Json.prettyPrint(v)}")
        }), Duration.Inf)
        1 === 1
      }
    }
  }
}
