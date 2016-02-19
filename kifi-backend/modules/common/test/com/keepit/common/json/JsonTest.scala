package com.keepit.common.json

import com.keepit.common.db.ExternalId
import com.keepit.model._
import org.specs2.mutable.Specification
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.Try

class JsonTest extends Specification {
  private case class Foo(x: Int, y: Double, z: String)
  private object Foo { implicit val format = Json.format[Foo] }
  "json package" should {
    "format JsObject maps" in {
      val f = TraversableFormat.mapFormat[ExternalId[Foo], Foo](_.id, s => Try(ExternalId[Foo](s)).toOption)
      val obj = Map[ExternalId[Foo], Foo](
        ExternalId("180922ae-7aab-4622-b4ad-131e9c901fa6") -> Foo(1, 2.0, "3"),
        ExternalId("b80511f6-8248-4799-a17d-f86c1508c90d") -> Foo(42, 19.19, "wapiti")
      )
      val js = Json.obj(
        "180922ae-7aab-4622-b4ad-131e9c901fa6" -> Json.obj("x" -> 1, "y" -> 2.0, "z" -> "3"),
        "b80511f6-8248-4799-a17d-f86c1508c90d" -> Json.obj("x" -> 42, "y" -> 19.19, "z" -> "wapiti")
      )
      "write" in {
        f.writes(obj) === js
      }
      "read" in {
        f.reads(js).asEither === Right(obj)
      }
      "fail on garbage" in {
        f.reads(Json.obj("180922ae-7aab-4622-b4ad-131e9c901fa6" -> "pqrs")).asEither must beLeft
        f.reads(Json.obj("asdf" -> Json.obj("x" -> 42, "y" -> 19.19, "z" -> "wapiti"))).asEither must beLeft
        f.reads(Json.obj("asdf" -> "pqrs")).asEither must beLeft
      }
    }
    "robustly deserialize collections" in {
      "robustly handle js arrays" in {
        val myFormat = TraversableFormat.safeArrayReads[OrganizationRole]
        Json.arr("member", "admin", "garbage").as[Seq[OrganizationRole]](myFormat) === Seq(OrganizationRole.MEMBER, OrganizationRole.ADMIN)
      }
      "robustly handle simple js objects" in {
        val myFormat = TraversableFormat.safeObjectReads[OrganizationRole, Feature](OrganizationRole.reads, Feature.reads)
        val input = Json.obj("member" -> "view_organization", "garbage_role" -> "view_members", "admin" -> "garbage_feature")
        val expected = Map(OrganizationRole.MEMBER -> StaticFeature.ViewOrganization)
        input.as[Map[OrganizationRole, Feature]](myFormat) === expected
      }
      "robustly handle complex js objects" in {
        def settingsReads(f: Feature): Reads[FeatureSetting] = f.settingReads
        val myFormat = TraversableFormat.safeConditionalObjectReads[Feature, FeatureSetting](Feature.reads, settingsReads)
        val input = Json.obj("view_organization" -> "members", "force_edit_libraries" -> "anyone")
        val expected = Map(StaticFeature.ViewOrganization -> StaticFeatureSetting.MEMBERS)
        input.as[Map[Feature, FeatureSetting]](myFormat) === expected
      }
    }
    "robustly deserialize enumerated items" in {
      "work" in {
        val myReads = EnumFormat.reads(str => OrganizationRole.all.find(_.value == str), OrganizationRole.all.map(_.value))
        JsString("admin").as[OrganizationRole](myReads) === OrganizationRole.ADMIN
        JsString("asdf").as[OrganizationRole](myReads) must throwA[JsResultException]

        JsString("admin").asOpt[OrganizationRole](myReads) === Some(OrganizationRole.ADMIN)
        JsString("asdf").asOpt[OrganizationRole](myReads) === None

        JsString("admin").validate[OrganizationRole](myReads) === JsSuccess(OrganizationRole.ADMIN)
        JsString("asdf").validate[OrganizationRole](myReads) must haveClass[JsError]
      }
    }
    "robustly deserialize optional fields" in {
      "work" in {
        import com.keepit.common.json.ReadIfPossible.PimpedJsPath
        case class Foo(x: Option[Int], y: Option[Int])
        object Foo {
          val reads = (
            (__ \ 'x).readIfPossible[Int] and
            (__ \ 'y).readIfPossible[Int]
          )(Foo.apply _)
        }

        val tests = Seq(
          Json.obj() -> Foo(None, None),
          Json.obj("x" -> 1) -> Foo(Some(1), None),
          Json.obj("y" -> 2) -> Foo(None, Some(2)),
          Json.obj("x" -> 1, "y" -> 2) -> Foo(Some(1), Some(2)),
          Json.obj("x" -> 1, "y" -> "bar") -> Foo(Some(1), None)
        )
        tests.foreach {
          case (inp, expected) => inp.asOpt[Foo](Foo.reads) === Some(expected)
        }
        1 === 1
      }
    }
    "drop fields" in {
      "work" in {
        import DropField.PimpedJsPath
        case class Foo(x: Int, y: Int, z: Int)
        object Foo {
          val normalFormat = (
            (__ \ 'x).format[Int] and
            (__ \ 'y).format[Int] and
            (__ \ 'z).format[Int]
          )(Foo.apply, unlift(Foo.unapply))
          val dropFormat = (
            (__ \ 'x).format[Int] and
            (__ \ 'y).dropField[Int](5) and
            (__ \ 'z).format[Int]
          )(Foo.apply, unlift(Foo.unapply))
        }

        val x = Foo(1, 2, 3)
        Foo.normalFormat.writes(x) === Json.obj("x" -> 1, "y" -> 2, "z" -> 3)
        Foo.dropFormat.writes(x) === Json.obj("x" -> 1, "z" -> 3)

        Foo.normalFormat.reads(Json.obj("x" -> 1, "y" -> 2, "z" -> 3)) === JsSuccess(x)
        Foo.dropFormat.reads(Json.obj("x" -> 1, "z" -> 3)) === JsSuccess(x.copy(y = 5))
        Foo.dropFormat.reads(Json.obj("x" -> 1, "y" -> 2, "z" -> 3)) === JsSuccess(x.copy(y = 5))
      }
    }
  }

}
