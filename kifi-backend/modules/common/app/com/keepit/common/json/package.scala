package com.keepit.common

import com.keepit.common.healthcheck.AirbrakeNotifierStatic
import play.api.libs.json._
import play.api.data.validation.ValidationError

import scala.util.{Success, Failure, Try}

package object json {
  object ReadIfPossible {
    def emptyReads[T] = Reads { j => JsSuccess(Option.empty[T]) }
    implicit class PimpedJsPath(jsp: JsPath) {
      // validation with readIfPossible ALWAYS succeeds, be careful when using it
      def readIfPossible[T](implicit reads: Reads[T]): Reads[Option[T]] = {
        jsp.readNullable[T] orElse emptyReads
      }
      def formatIfPossible[T](implicit reads: Reads[T], writes: Writes[T]): OFormat[Option[T]] = {
        OFormat(readIfPossible, jsp.writeNullable)
      }
    }
  }
  object EnumFormat {
    def reads[T](fromStr: (String => Option[T]), domain: Set[String] = Set.empty): Reads[T] = Reads { j =>
      def errMsg = if (domain.nonEmpty) s"$j is not one of these: $domain" else s"Could not recognize $j"
      for {
        str <- j.validate[String]
        v <- fromStr(str).map(JsSuccess(_)).getOrElse(JsError(ValidationError(errMsg)))
      } yield v
    }
    def writes[T](toStr: T => String): Writes[T] = Writes { v => JsString(toStr(v)) }
    def format[T](fromStr: (String => Option[T]), toStr: T => String, domain: Set[String] = Set.empty) = Format(
      reads(fromStr, domain), writes(toStr)
    )
  }
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
    def keyedWrites[A, B](strA: String, strB: String)(implicit aWrites: Writes[A], bWrites: Writes[B]): OWrites[Either[A, B]] = OWrites {
      case Left(a) => Json.obj(strA -> aWrites.writes(a))
      case Right(b) => Json.obj(strB -> bWrites.writes(b))
    }
    def keyedFormat[A, B](strA: String, strB: String)(implicit aFormat: Format[A], bFormat: Format[B]): OFormat[Either[A, B]] =
      OFormat(keyedReads[A,B](strA, strB), keyedWrites[A,B](strA, strB))
  }

  object TraversableFormat {
    private def materialize[U, T <: Traversable[U]](implicit traversableFormatter: Format[T]) = traversableFormatter
    def seq[U](implicit formatter: Format[U]) = materialize[U, Seq[U]]
    def set[U](implicit formatter: Format[U]) = materialize[U, Set[U]]

    def mapReads[K, V](fromKey: String => Option[K])(implicit vReads: Reads[V]): Reads[Map[K,V]] = Reads { j =>
      j.validate[JsObject].flatMap { jsObj =>
        val pairs = jsObj.fields.map {
          case (jsK, jsV) => for { k <- fromKey(jsK); v <- vReads.reads(jsV).asOpt } yield k -> v
        }
        pairs.zipWithIndex.find(_._1.isEmpty) match {
          case Some((_, failedIdx)) => JsError(s"Could not parse ${jsObj.fields(failedIdx)} as a key-value pair")
          case None => JsSuccess(pairs.map(_.get).toMap)
        }
      }
    }
    def mapWrites[K, V](toKey: K => String)(implicit vWrites: Writes[V]): Writes[Map[K,V]] = Writes { o =>
      JsObject(o.toSeq.map { case (k,v) => toKey(k) -> vWrites.writes(v) })
    }
    def mapFormat[K, V](toKey: K => String, fromKey: String => Option[K])(implicit vFormat: Format[V]): Format[Map[K,V]] =
      Format(mapReads(fromKey), mapWrites(toKey))

    def safeArrayReads[T](implicit reads: Reads[T]): Reads[Seq[T]] = Reads { jsv =>
      jsv.validate[Seq[JsValue]].map { arr =>
        val vs = arr.map { v => Try(v.as[T]) }
        vs.collect { case Failure(fail) => AirbrakeNotifierStatic.notify(fail) }
        vs.collect { case Success(v) => v }
      }
    }
    def safeSetReads[T](implicit reads: Reads[T]): Reads[Set[T]] = safeArrayReads[T].map(_.toSet)

    def safeObjectReads[K,V](implicit keyReads: Reads[K], valReads: Reads[V]): Reads[Map[K,V]] = Reads { jsv =>
      jsv.validate[Map[String, JsValue]].map { arr =>
        val vs = arr.map {
          case (k, v) => Try {
            val key = keyReads.reads(JsString(k)).get
            val value = valReads.reads(v).get
            key -> value
          }
        }
        vs.collect { case Failure(fail) => AirbrakeNotifierStatic.notify(fail) }
        vs.collect { case Success(v) => v }.toMap
      }
    }

    def safeConditionalObjectReads[K,V](implicit keyReads: Reads[K], valReads: (K => Reads[V])): Reads[Map[K,V]] = Reads { jsv =>
      jsv.validate[Map[String, JsValue]].map { arr =>
        val vs = arr.map {
          case (k, v) => Try {
            val key = keyReads.reads(JsString(k)).get
            val value = valReads(key).reads(v).get
            key -> value
          }
        }
        vs.collect { case Failure(fail) => AirbrakeNotifierStatic.notify(fail) }
        vs.collect { case Success(v) => v }.toMap
      }
    }
  }

  val readUnit: Reads[Unit] = Reads { _ => JsSuccess(Unit) }
  val formatNone: Format[None.type] = {
    Format(
      Reads {
        case JsNull => JsSuccess(None)
        case unknown => JsError(s"Expected JsNull for None, instead: $unknown")
      },
      Writes(None => JsNull)
    )
  }

  object TestHelper {
    def deepCompare(a: JsValue, b: JsValue, path: String = "obj"): Option[String] = {
      (a.asOpt[JsObject], b.asOpt[JsObject]) match {
        case (Some(aObj), Some(bObj)) =>
          (aObj.keys ++ bObj.keys).flatMap(k => deepCompare(aObj \ k, bObj \ k, s"$path.$k")).headOption
        case _ =>
          (a.asOpt[JsArray], b.asOpt[JsArray]) match {
            case (Some(aArr), Some(bArr)) if aArr.value.length != bArr.value.length =>
              Some(s"$path: lengths unequal")
            case (Some(aArr), Some(bArr)) =>
              (aArr.value zip bArr.value).zipWithIndex.flatMap { case ((av, bv), i) => deepCompare(av, bv, s"$path[$i]") }.headOption
            case _ if a != b =>
              Some(s"$path: $a != $b")
            case _ => None
          }
      }
    }

  }
}
