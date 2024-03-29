package com.keepit.export

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.json.EitherFormat
import com.keepit.common.util.{ EnglishProperNouns, TimedComputation }
import com.keepit.discussion.Message
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

class FullExportProducerTest extends Specification with ShoeboxTestInjector with ShoeboxInjectionHelpers {
  def modules = Seq(
    FakeExecutionContextModule()
  )

  "FullExportProducer" should {
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
      } :+ KeepFactory.keep().withUser(owner).saved
      (owner, org, libs, keeps)
    }
    "provide a streaming export" in {
      withDb(modules: _*) { implicit injector =>
        val (user, org, libs, keeps) = setup()
        val stream = TimedComputation.sync(inject[FullExportProducer].fullExport(user.id.get))
        val root = TimedComputation.sync(Await.result(FullStaticExport.consume(stream.value), Duration.Inf))

        // println(s"starting the stream took ${stream.millis}")
        // println(s"computing the full export took ${root.millis}")
        // println(Json.prettyPrint(FullStaticExport.writes.writes(root.value)))

        root.value.spaces.flatMap(_.libraries).map(_.library.id.get).toSet === libs.map(_.id.get).toSet
        (root.value.keeps ++ root.value.spaces.flatMap(_.libraries.flatMap(_.keeps))).map(_.keep.id.get).toSet === keeps.map(_.id.get).toSet
      }
    }
  }
}

object FullStaticExport {
  final case class Root(user: BasicUser, spaces: Seq[SpaceExport], keeps: Seq[KeepExport])
  final case class SpaceExport(space: Either[BasicUser, BasicOrganization], libraries: Seq[LibraryExport])
  final case class LibraryExport(library: Library, keeps: Seq[KeepExport])
  final case class KeepExport(keep: Keep, messages: Seq[Message], uri: Option[RoverUriSummary])

  def consume(root: FullStreamingExport.Root)(implicit ec: ExecutionContext): Future[FullStaticExport.Root] = {
    root.spaces.run(Iteratee.getChunks).flatMap { spaces =>
      Future.sequence(spaces.map { space =>
        space.libraries.run(Iteratee.getChunks).flatMap { libraries =>
          Future.sequence(libraries.map { lib =>
            lib.keeps.run(Iteratee.getChunks).map { keeps =>
              FullStaticExport.LibraryExport(lib.library, keeps.map { keep =>
                FullStaticExport.KeepExport(keep.keep, keep.messages, keep.uri)
              })
            }
          }).map { libraries =>
            FullStaticExport.SpaceExport(space.space, libraries)
          }
        }
      }).flatMap { spaces =>
        root.looseKeeps.run(Iteratee.getChunks).map { keeps =>
          FullStaticExport.Root(root.user, spaces, keeps.map { keep =>
            FullStaticExport.KeepExport(keep.keep, keep.messages, keep.uri)
          })
        }
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
