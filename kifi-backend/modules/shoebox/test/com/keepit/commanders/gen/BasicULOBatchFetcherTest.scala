package com.keepit.commanders.gen

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.{ BatchComputable, BatchFetchable, DescriptionElements }
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.functional.syntax._
import play.api.libs.json.{ Format, Json }

class BasicULOBatchFetcherTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit def imgConfig(implicit injector: Injector): S3ImageConfig = inject[S3ImageConfig]
  "BasicULOBatchFetcher" should {
    "work at all" in {
      withDb() { implicit injector =>
        val ulo = inject[BasicULOBatchFetcher]
        def getStuff: BatchFetchable[Int] = { BatchFetchable.empty.map(_ => 42) }
        ulo.run(getStuff) === 42
      }
    }
    "get basic users/libs/orgs" in {
      withDb() { implicit injector =>
        val (user, org, lib) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Ryan", "Brewster").saved
          val org = OrganizationFactory.organization().withOwner(user).withName("Brewstercorp").saved
          val lib = LibraryFactory.library().withOwner(user).withOrganization(org).withName("General").saved
          (user, org, lib)
        }
        val ulo = inject[BasicULOBatchFetcher]
        def getStuff: BatchFetchable[Option[DescriptionElements]] = {
          import DescriptionElements._
          val userOpt = BatchFetchable.user(user.id.get)
          val libOpt = BatchFetchable.library(lib.id.get)
          val orgOpt = BatchFetchable.org(org.id.get)
          def assemble(uo: Option[BasicUser], lo: Option[BasicLibrary], oo: Option[BasicOrganization]) = {
            for (u <- uo; l <- lo; o <- oo) yield DescriptionElements(u, "added a keep to", l, "in", o)
          }
          (userOpt and libOpt and orgOpt)(assemble _)
        }
        val ans = ulo.run(getStuff).get
        // println(Json.prettyPrint(DescriptionElements.flatWrites.writes(ans)))
        DescriptionElements.formatPlain(ans) === "Ryan added a keep to General in Brewstercorp"
      }
    }
    "serialize batch-computables" in {
      withDb() { implicit injector =>
        val (user, org, lib) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Ryan", "Brewster").withUsername("ryanpbrewster").saved
          val org = OrganizationFactory.organization().withOwner(user).withName("Brewstercorp").saved
          val lib = LibraryFactory.library().withOwner(user).withOrganization(org).withName("General").saved
          (user, org, lib)
        }
        val ulo = inject[BasicULOBatchFetcher]

        object InputObject {
          case class Input(uId: Id[User], lId: Id[Library], oId: Id[Organization])
          implicit val inputFormat: Format[Input] = Json.format[Input]
        }
        import InputObject._

        def getStuff(i: Input): BatchFetchable[DescriptionElements] = {
          import DescriptionElements._
          (BatchFetchable.user(i.uId) and BatchFetchable.library(i.lId) and BatchFetchable.org(i.oId)).tupled.map {
            case (Some(u), Some(l), Some(o)) =>
              DescriptionElements(u, "added a keep to", l, "in", o)
            case _ => throw new RuntimeException("missing id :(")
          }
        }
        val input = Input(user.id.get, lib.id.get, org.id.get)

        val ans = ulo.run(getStuff(input))
        val computable = ulo.compute(input)(getStuff)

        val serializedComputable = Json.toJson(computable)

        println(Json.prettyPrint(serializedComputable))

        val deserializedComputable = serializedComputable.as[BatchComputable[Input, DescriptionElements]]
        Json.toJson(deserializedComputable.run(getStuff)) === Json.toJson(ans)
      }
    }
  }
}
