package com.keepit.export

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.json.EitherFormat
import com.keepit.common.util.{EnglishProperNouns, TimedComputation}
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.rover.model.RoverUriSummary
import com.keepit.social.BasicUser
import com.keepit.test.{ShoeboxInjectionHelpers, ShoeboxTestInjector}
import org.specs2.mutable.Specification
import play.api.libs.iteratee.{Enumeratee, Enumerator, Iteratee}
import play.api.libs.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class FullExportCommanderTest extends Specification with ShoeboxTestInjector with ShoeboxInjectionHelpers {
  def modules = Seq(
    FakeExecutionContextModule()
  )

  "KeepExportCommander" should {
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
    "provide a streaming export" in {
      withDb(modules: _*) { implicit injector =>
        val (user, org, libs, keeps) = setup()
        val stream = TimedComputation.sync(inject[FullExportCommander].fullExport(user.id.get))
        val root = TimedComputation.sync(Await.result(FullStaticExport.consume(stream.value), Duration.Inf))

        // println(s"starting the stream took ${stream.millis}")
        // println(s"computing the full export took ${root.millis}")
        // println(Json.prettyPrint(FullStaticExport.writes.writes(root.value)))

        root.value.spaces.flatMap(_.libraries).map(_.library.id.get).toSet === libs.map(_.id.get).toSet
        root.value.spaces.flatMap(_.libraries.flatMap(_.keeps)).map(_.keep.id.get).toSet === keeps.map(_.id.get).toSet
      }
    }
    "help me figure out my life" in {
      def fullIndexPage(export: FullStreamingExport.Root): Enumerator[(String, JsValue)] = {
        implicit val spaceWrites = EitherFormat.keyedWrites[BasicUser, BasicOrganization]("user", "org")
        export.spaces.map(_.space).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { spaces =>
          "index" -> Json.obj(
            "me" -> export.user,
            "spaces" -> JsArray(spaces.map { space =>
              val partialSpace = spaceWrites.writes(space)
              partialSpace
            })
          )
        })
      }
      def fullSpacePage(space: FullStreamingExport.SpaceExport): Enumerator[(String, JsValue)] = {
        implicit val spaceWrites = EitherFormat.keyedWrites[BasicUser, BasicOrganization]("user", "org")
        val path = space.space.fold(u => "users/" + u.externalId.id, o => "orgs/" + o.orgId.id)
        space.libraries.map(_.library).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { libs =>
          val fullSpace = spaceWrites.writes(space.space)
          path -> Json.obj(
            "space" -> fullSpace,
            "libraries" -> JsArray(libs.map { lib =>
              val partialLibrary = Json.obj(
                "id" -> lib.id.get,
                "name" -> lib.name,
                "description" -> lib.description
              )
              partialLibrary
            })
          )
        })
      }
      def fullLibraryPage(library: FullStreamingExport.LibraryExport): Enumerator[(String, JsValue)] = {
        val path = "libraries/" + library.library.id.get.id.toString
        val fullLibrary = Json.obj("name" -> library.library.name)
        library.keeps.map(_.keep).through(Enumeratee.grouped(Iteratee.getChunks)).through(Enumeratee.map { keeps =>
          path -> Json.obj(
            "library" -> fullLibrary,
            "keeps" -> JsArray(keeps.map { keep =>
              val partialKeep = Json.obj(
                "id" -> keep.id.get,
                "title" -> keep.title,
                "url" -> keep.url,
                "keptAt" -> keep.keptAt
              )
              partialKeep
            })
          )
        })
      }
      def fullKeepPage(keep: FullStreamingExport.KeepExport): Enumerator[(String, JsValue)] = {
        val path = "keeps/" + keep.keep.id.get.id.toString
        Enumerator.empty
      }
      withDb(modules: _*) { implicit injector =>
        val (user, org, libs, keeps) = setup()
        val export = TimedComputation.sync(inject[FullExportCommander].fullExport(user.id.get)).value
        val fileEnum = export.spaces.flatMap { space =>
          space.libraries.flatMap { library =>
            library.keeps.flatMap { keep =>
              fullKeepPage(keep)
            } andThen fullLibraryPage(library)
          } andThen fullSpacePage(space)
        } andThen fullIndexPage(export)
        Await.result(fileEnum.run(Iteratee.foreach {
          case (filename, js) =>
          // println(filename)
          // println(Json.prettyPrint(js))
          // println("==================================")
        }), Duration.Inf)
        1 === 1
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
