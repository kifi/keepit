package com.keepit.common.net

import com.keepit.common.crypto.PublicId
import com.keepit.common.net.QsPath._
import com.keepit.common.net.QsWrites._
import com.keepit.model.{ Library, LibraryVisibility }
import org.specs2.mutable.Specification
import play.api.libs.functional.syntax._

class QsFormatTest extends Specification {
  "QsFormat" should {
    "parse a raw string" in {
      QsValue.parse("a=5&b=10&c=15").getRight must beSome(QsValue.obj("a" -> 5, "b" -> 10, "c" -> 15))
      QsValue.parse("a=5&a=10&a=15").getRight must beSome(QsValue.obj("a" -> 15))
      QsValue.parse("a.x=5&a.y=10&a.z=15").getRight must beSome(QsValue.obj("a" -> QsValue.obj("x" -> 5, "y" -> 10, "z" -> 15)))
    }
    "stringify" in {
      QsValue.stringify(QsValue.obj("a" -> 5, "b" -> "asdf")) === "a=5&b=asdf"
      QsValue.stringify(QsValue.obj("a" -> 5, "b" -> "asdf", "c" -> QsNull)) === "a=5&b=asdf"
      QsValue.stringify(QsValue.obj("a" -> 5, "b" -> "asdf", "c" -> QsValue.obj("x" -> 42, "y" -> true, "z" -> QsNull))) === "a=5&b=asdf&c.x=42&c.y=true"
    }
    "format a simple case class" in {
      final case class Foo(x: Int, y: String)
      val qsf: QsFormat[Foo] = (
        (q__ \ "x").qsf[Int] and
        (q__ \ "y").qsf[String]
      )(Foo.apply, unlift(Foo.unapply))
      qsf.writes.writes(Foo(5, "five")) === QsValue.obj("x" -> 5, "y" -> "five")
      qsf.reads.reads(QsValue.obj("x" -> 5, "y" -> "five")).getRight must beSome(Foo(5, "five"))
    }
    "format a complex case class" in {
      final case class A(x: Int, y: LibraryVisibility, z: String)
      implicit val libVisQSF: QsFormat[LibraryVisibility] = QsFormat.direct(LibraryVisibility.get, _.value)
      val default = A(42, LibraryVisibility.SECRET, "rpb")
      val qsf: QsFormat[A] = (
        (q__ \ "x").qsfOpt[Int].inmap[Int](_ getOrElse default.x, Some(_)) and
        (q__ \ "y").qsfOpt[LibraryVisibility].inmap[LibraryVisibility](_ getOrElse default.y, Some(_)) and
        (q__ \ "z").qsfOpt[String].inmap[String](_ getOrElse default.z, Some(_))
      )(A.apply, unlift(A.unapply))

      qsf.reads.reads(QsNull).getRight must beSome(default)
      qsf.reads.reads(QsValue.obj("x" -> 14, "y" -> "published", "z" -> "asdf")).getRight must beSome(A(14, LibraryVisibility.PUBLISHED, "asdf"))
      qsf.reads.reads(QsValue.obj("x" -> "pqrs")).getRight must beNone
    }
    "format a horrifying nested case class" in {
      final case class A(b: B, c: Option[C])
      final case class B(w: Int, x: Option[String])
      final case class C(y: Boolean, z: PublicId[Library])
      implicit val qsfLibPubId: QsFormat[PublicId[Library]] = QsFormat.direct(idStr => Library.validatePublicId(idStr).toOption, _.id)
      implicit val qsfB: QsFormat[B] = ((q__ \ "w").qsf[Int] and (q__ \ "x").qsfOpt[String])(B.apply, unlift(B.unapply))
      implicit val qsfC: QsFormat[C] = ((q__ \ "y").qsf[Boolean] and (q__ \ "z").qsf[PublicId[Library]])(C.apply, unlift(C.unapply))
      val qsf: QsFormat[A] = (
        (q__ \ "b").qsf[B] and
        (q__ \ "c").qsfOpt[C]
      )(A.apply, unlift(A.unapply))

      qsf.reads.reads(QsNull).getRight must beNone
      qsf.reads.reads(QsValue.obj("b" -> QsValue.obj("w" -> 5))).getRight must beSome(A(B(5, None), None))

      val sadnessQSV = QsValue.obj("b" -> QsValue.obj("w" -> 5, "x" -> "asdf"), "c" -> QsValue.obj("y" -> true, "z" -> "l42424242"))
      val sadnessABC = A(B(5, Some("asdf")), Some(C(true, PublicId("l42424242"))))
      qsf.reads.reads(sadnessQSV).getRight must beSome(sadnessABC)
    }
  }
}
