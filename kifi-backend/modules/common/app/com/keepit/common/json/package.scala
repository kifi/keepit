package com.keepit.common

import play.api.libs.json._
import play.api.data.validation.ValidationError

package object json {

  object KeyFormat {
    def key1Reads[A](strA: String)(implicit aReads: Reads[A]): Reads[A] = Reads[A] {
      case obj: JsObject => for {
        a <- aReads.reads(obj \ strA)
      } yield a
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError(s"Expected a JsObject"))))
    }
    def key1Writes[A](strA: String)(implicit aWrites: Writes[A]): Writes[(A)] = Writes[(A)] {
      tup: (A) => JsObject(Seq(strA -> aWrites.writes(tup)))
    }
    def key1Format[A](strA: String)(implicit aFormat: Format[A]): Format[(A)] = {
      Format(key1Reads[A](strA), key1Writes[A](strA))
    }

    def key2Reads[A, B](strA: String, strB: String)(implicit aReads: Reads[A], bReads: Reads[B]): Reads[(A, B)] = Reads[(A, B)] {
      case obj: JsObject => for {
        a <- aReads.reads(obj \ strA)
        b <- bReads.reads(obj \ strB)
      } yield (a, b)
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError(s"Expected a JsObject"))))
    }
    def key2Writes[A, B](strA: String, strB: String)(implicit aWrites: Writes[A], bWrites: Writes[B]): Writes[(A, B)] = Writes[(A, B)] {
      tup: (A, B) => JsObject(Seq(strA -> aWrites.writes(tup._1), strB -> bWrites.writes(tup._2)))
    }
    def key2Format[A, B](strA: String, strB: String)(implicit aFormat: Format[A], bFormat: Format[B]): Format[(A, B)] = {
      Format(key2Reads[A, B](strA, strB), key2Writes[A, B](strA, strB))
    }

    def key3Reads[A, B, C](strA: String, strB: String, strC: String)(implicit aReads: Reads[A], bReads: Reads[B], cReads: Reads[C]): Reads[(A, B, C)] = Reads[(A, B, C)] {
      case obj: JsObject => for {
        a <- aReads.reads(obj \ strA)
        b <- bReads.reads(obj \ strB)
        c <- cReads.reads(obj \ strC)
      } yield (a, b, c)
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError(s"Expected a JsObject"))))
    }
    def key3Writes[A, B, C](strA: String, strB: String, strC: String)(implicit aWrites: Writes[A], bWrites: Writes[B], cWrites: Writes[C]): Writes[(A, B, C)] = Writes[(A, B, C)] {
      tup: (A, B, C) => JsObject(Seq(strA -> aWrites.writes(tup._1), strB -> bWrites.writes(tup._2), strC -> cWrites.writes(tup._3)))
    }
    def key3Format[A, B, C](strA: String, strB: String, strC: String)(implicit aFormat: Format[A], bFormat: Format[B], cFormat: Format[C]): Format[(A, B, C)] = {
      Format(key3Reads[A, B, C](strA, strB, strC), key3Writes[A, B, C](strA, strB, strC))
    }
  }
  object TupleFormat {
    /* Inspired from https://coderwall.com/p/orci8g */
    implicit def tuple2Reads[A, B](implicit aReads: Reads[A], bReads: Reads[B]): Reads[(A, B)] = Reads[(A, B)] {
      case JsArray(arr) if arr.size == 2 => for {
        a <- aReads.reads(arr(0))
        b <- bReads.reads(arr(1))
      } yield (a, b)
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError("Expected array of two elements"))))
    }

    implicit def tuple2Writes[A, B](implicit aWrites: Writes[A], bWrites: Writes[B]): Writes[(A, B)] = Writes[(A, B)] {
      tup: (A, B) => JsArray(Seq(aWrites.writes(tup._1), bWrites.writes(tup._2)))
    }

    implicit def tuple2Format[A, B](implicit aFormat: Format[A], bFormat: Format[B]): Format[(A, B)] = {
      Format(tuple2Reads[A, B], tuple2Writes[A, B])
    }

    implicit def tuple3Reads[A, B, C](implicit aReads: Reads[A], bReads: Reads[B], cReads: Reads[C]): Reads[(A, B, C)] = Reads[(A, B, C)] {
      case JsArray(arr) if arr.size == 3 => for {
        a <- aReads.reads(arr(0))
        b <- bReads.reads(arr(1))
        c <- cReads.reads(arr(2))
      } yield (a, b, c)
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError("Expected array of three elements"))))
    }

    implicit def tuple3Writes[A, B, C](implicit aWrites: Writes[A], bWrites: Writes[B], cWrites: Writes[C]): Writes[(A, B, C)] = new Writes[(A, B, C)] {
      def writes(tuple: (A, B, C)) = JsArray(Seq(aWrites.writes(tuple._1), bWrites.writes(tuple._2), cWrites.writes(tuple._3)))
    }

    implicit def tuple3Format[A, B, C](implicit aFormat: Format[A], bFormat: Format[B], cFormat: Format[C]): Format[(A, B, C)] = {
      Format(tuple3Reads[A, B, C], tuple3Writes[A, B, C])
    }
  }

  implicit class PimpedFormat[A](format: Format[A]) {
    private implicit val implicitFormat: Format[A] = format

    def convert[B](atob: A => B, btoa: B => A): Format[B] = Format(
      Reads(Json.fromJson[A](_).map(atob)),
      Writes(obj => Json.toJson(btoa(obj)))
    )
  }

  @inline private def canBeOmitted(value: JsValue): Boolean = value match {
    case JsNull | JsBoolean(false) | JsString("") | JsArray(Seq()) => true
    case JsNumber(zero) if zero == 0 => true
    case _ => false
  }

  // Be careful using this; only use it if the expectations on the client side coincide with what
  // this actually does. This does not come by default or for free.
  def aggressiveMinify(fullJson: JsValue): JsValue = fullJson match {
    case obj: JsObject => JsObject(obj.fields.filterNot { case (key, value) => canBeOmitted(value) })
    case _ => fullJson
  }

  /* Be careful, this will always attempt to deserialize to Left first, and then to Right if Left has failed.
  This could lead to an incorrect behavior if the Right type can be serialized to a valid Left type
  So we're not providing this Format as an implicit on purpose. */

  object EitherFormat {
    def apply[A, B](implicit leftFormat: Format[A], rightFormat: Format[B]) = new Format[Either[A, B]] {
      def reads(json: JsValue) = json.validate[A].map(Left(_)) orElse json.validate[B].map(Right(_))
      def writes(either: Either[A, B]) = either.left.map(Json.toJson(_)).right.map(Json.toJson(_)).merge
    }

    def keyedReads[A, B](strA: String, strB: String)(implicit aReads: Reads[A], bReads: Reads[B]): Reads[Either[A, B]] = Reads[Either[A, B]] {
      case obj: JsObject if obj.keys.intersect(Set(strA, strB)).size == 1 =>
        obj.keys match {
          case keys if keys.contains(strA) => aReads.reads(obj \ strA).map(Left(_))
          case keys if keys.contains(strB) => bReads.reads(obj \ strB).map(Right(_))
        }
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError(s"Expected a JsObject with a single field of [$strA, $strB]"))))
    }
  }

  object TraversableFormat {
    private def materialize[U, T <: Traversable[U]](implicit traversableFormatter: Format[T]) = traversableFormatter
    def seq[U](implicit formatter: Format[U]) = materialize[U, Seq[U]]
    def set[U](implicit formatter: Format[U]) = materialize[U, Set[U]]
  }
}
