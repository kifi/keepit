package com.keepit.export

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.util.{ EnglishProperNouns, TimedComputation }
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.rover.model.RoverUriSummary
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration.Duration

class FullExportCommanderTest extends Specification with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule()
  )

  "KeepExportCommander" should {
    def setup()(implicit injector: Injector): (User, Organization, Seq[Library], Seq[Keep]) = db.readWrite { implicit session =>
      val owner = UserFactory.user().withName("Ryan", "Brewster").saved
      val user = UserFactory.user().withName("Rando", "McRanderson").saved
      val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(user)).withName("Brewstercorp").saved
      val libs = {
        val libNames = EnglishProperNouns.planets.toIterator
        Seq(
          LibraryFactory.library().withName(libNames.next()).withOwner(owner).withCollaborators(Seq(user)).withOrganization(org).withVisibility(LibraryVisibility.PUBLISHED).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(owner).withCollaborators(Seq(user)).withOrganization(org).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(owner).withCollaborators(Seq(user)).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(user).withCollaborators(Seq(owner)).withOrganization(org).withVisibility(LibraryVisibility.SECRET).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(user).withCollaborators(Seq(owner)).withOrganization(org).saved,
          LibraryFactory.library().withName(libNames.next()).withOwner(user).withCollaborators(Seq(owner)).saved
        )
      }
      val keeps = libs.flatMap { lib =>
        Seq(
          KeepFactory.keep().withUser(owner).withLibrary(lib).saved,
          KeepFactory.keep().withUser(user).withLibrary(lib).saved
        )
      }
      (owner, org, libs, keeps)
    }
    "provide a streaming export" in {
      withDb(modules: _*) { implicit injector =>
        val (user, org, libs, keeps) = setup()
        val stream = TimedComputation.sync(inject[FullExportCommander].fullExport(user.id.get))
        val root = TimedComputation.sync(Await.result(FullStaticExport.consume(stream.value), Duration.Inf))
        /*
        println(s"starting the stream took ${stream.millis}")
        println(s"computing the full export took ${root.millis}")
        println(Json.prettyPrint(FullStaticExport.writes.writes(root.value)))
        */
        root.value.spaces.flatMap(_.libraries).map(_.library.id.get).toSet must containAllOf(libs.map(_.id.get))
        root.value.spaces.flatMap(_.libraries.flatMap(_.keeps)).map(_.keep.id.get).toSet must containAllOf(keeps.map(_.id.get))
      }
    }
  }
}

object FullStaticExport {
  final case class Root(user: BasicUser, spaces: Seq[SpaceExport])
  final case class SpaceExport(space: Either[BasicUser, BasicOrganization], libraries: Seq[LibraryExport])
  final case class LibraryExport(library: Library, keeps: Seq[KeepExport])
  final case class KeepExport(keep: Keep, uri: Option[RoverUriSummary])

  def consume(root: FullStreamingExport.Root)(implicit ec: ExecutionContext): Future[FullStaticExport.Root] = {
    root.spaces.run(Iteratee.getChunks).flatMap { spaces =>
      Future.sequence(spaces.map { space =>
        space.libraries.run(Iteratee.getChunks).flatMap { libraries =>
          Future.sequence(libraries.map { lib =>
            lib.keeps.run(Iteratee.getChunks).map { keeps =>
              FullStaticExport.LibraryExport(lib.library, keeps.map { keep =>
                FullStaticExport.KeepExport(keep.keep, keep.uri)
              })
            }
          }).map { libraries =>
            FullStaticExport.SpaceExport(space.space, libraries)
          }
        }
      }).map { spaces =>
        FullStaticExport.Root(root.user, spaces)
      }
    }
  }

  implicit val writes: Writes[Root] = OWrites { root =>
    Json.obj(
      "user" -> root.user,
      "spaces" -> JsObject(root.spaces.map { space =>
        space.space.fold(_.fullName, _.name) -> JsObject(space.libraries.map { library =>
          library.library.name -> JsArray(library.keeps.map(keep => JsString(keep.keep.url)))
        })
      })
    )
  }
}
