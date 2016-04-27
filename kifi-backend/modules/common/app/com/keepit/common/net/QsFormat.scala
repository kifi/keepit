package com.keepit.common.net

import java.net.{ URLDecoder, URLEncoder }
import com.keepit.common.strings.UTF8

import com.keepit.common.util.RightBias._
import com.keepit.common.util.{ MapHelpers, RightBias }
import play.api.libs.functional._
import play.api.mvc.QueryStringBindable

import scala.util.Try

class QsFormat[T](val reads: QsReads[T], val writes: QsWrites[T])
class QsOFormat[T](override val reads: QsReads[T], override val writes: QsOWrites[T]) extends QsFormat(reads, writes)
object QsFormat {
  def apply[T](reads: QsReads[T], writes: QsWrites[T]) = new QsFormat(reads, writes)
  def direct[T](fromStr: String => Option[T], toStr: T => String = (t: T) => t.toString): QsFormat[T] = {
    QsFormat[T](
      QsReads[T] {
        case QsPrimitive(v) => fromStr(v).withLeft(QsError(s"could not deserialize $v"))
        case _ => RightBias.left(QsError("expected a primitive"))
      },
      QsWrites { t => QsPrimitive(toStr(t)) }
    )
  }

  implicit val string: QsFormat[String] = direct(Some(_), identity)
  implicit val int: QsFormat[Int] = direct(str => Try(str.toInt).toOption, _.toString)
  implicit val bool: QsFormat[Boolean] = direct(str => Try(str.toBoolean).toOption, _.toString)

  def binder[T](implicit qsf: QsOFormat[T]): QueryStringBindable[T] = new QueryStringBindable[T] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, T]] = {
      val qsv = params.toSeq.map {
        case (seg, v +: _) => QsValue.bump(seg)(QsPrimitive(v))
      }.foldLeft(QsValue.obj())(_ ++ _) \ key
      Some(qsf.reads.reads(qsv).fold(err => Left(err.msg), t => Right(t)))
    }
    override def unbind(key: String, value: T): String = {
      QsValue.stringify(QsValue.bump(key)(qsf.writes.writes(value)))
    }
  }
}
object QsOFormat {
  def apply[T](reads: QsReads[T], writes: QsOWrites[T]) = new QsOFormat[T](reads, writes)
  implicit val qsfFCB: FunctionalCanBuild[QsOFormat] = new FunctionalCanBuild[QsOFormat] {
    def apply[A, B](ma: QsOFormat[A], mb: QsOFormat[B]): QsOFormat[A ~ B] = {
      QsOFormat[A ~ B](QsReads.qsrFCB(ma.reads, mb.reads), QsOWrites.qswFCB(ma.writes, mb.writes))
    }
  }
  implicit val qsfFunctor: InvariantFunctor[QsOFormat] = new InvariantFunctor[QsOFormat] {
    def inmap[A, B](m: QsOFormat[A], f1: A => B, f2: B => A): QsOFormat[B] = {
      QsOFormat(QsReads.qsrFunctor.fmap(m.reads, f1), QsOWrites.qswFunctor.contramap(m.writes, f2))
    }
  }
}

final case class QsPath(segments: Seq[String]) {
  def canonical: String = segments.mkString(".")
  def +(that: QsPath) = QsPath(this.segments ++ that.segments)
  def \(seg: String) = QsPath(segments :+ seg)
  def qsf[T](implicit format: QsFormat[T]): QsOFormat[T] = QsOFormat(
    QsReads { qs => format.reads.reads(qs \\ this) },
    QsOWrites { t => QsValue.shift(this)(format.writes.writes(t)) }
  )

  def qsfOpt[T](implicit format: QsFormat[T]): QsOFormat[Option[T]] = QsOFormat(
    QsReads { qs =>
      (qs \\ this) match {
        case QsUndefined => RightBias.right(None)
        case actualValue => format.reads.reads(actualValue).map(Some(_))
      }
    },
    QsOWrites { to => QsValue.shift(this)(to.map(format.writes.writes).getOrElse(QsNull)) }
  )
}
object QsPath {
  val q__ = QsPath(Seq.empty)
  implicit val qspOrd: Ordering[QsPath] = Ordering.by(_.canonical)
}

sealed abstract class QsValue {
  def \(seg: String): QsValue
  def \\(path: QsPath): QsValue = path.segments.foldLeft(this)(_ \ _)
}
case object QsNull extends QsValue {
  def \(seg: String) = QsUndefined
}
case object QsUndefined extends QsValue {
  def \(seg: String) = QsUndefined
}
final case class QsPrimitive(value: String) extends QsValue {
  def \(seg: String) = QsUndefined
}
final case class QsObject(fields: Map[String, QsValue]) extends QsValue {
  def \(seg: String) = fields.getOrElse(seg, QsUndefined)
  def ++(that: QsObject): QsObject = QsObject(MapHelpers.unionWith[String, QsValue] {
    case (aObj: QsObject, bObj: QsObject) => aObj ++ bObj
    case (_, b) => b
  }(this.fields, that.fields))
}
object QsValue {
  final case class QsValueLike(value: QsValue)
  implicit def qsvlTrivial(value: QsValue): QsValueLike = QsValueLike(value)
  implicit def qsvlDerived[T](t: T)(implicit writes: QsWrites[T]): QsValueLike = QsValueLike(writes.writes(t))
  def obj(ps: (String, QsValueLike)*): QsObject = QsObject(ps.toMap.mapValues(_.value))

  def shift(path: QsPath)(qv: QsValue): QsObject = path.segments.init.foldRight(obj(path.segments.last -> qv)) {
    case (seg, v) => bump(seg)(v)
  }
  def bump(seg: String)(qv: QsValue): QsObject = obj(seg -> qv)

  private val fieldRegex = """(.*)=(.*)""".r
  def parse(str: String): RightBias[String, QsValue] = {
    val tokens = str.split('&').toSeq
    val valuesByPath: RightBias[String, Seq[(String, String)]] = tokens.fragileMap[String, (String, String)] {
      case fieldRegex(k, v) => RightBias.right(URLDecoder.decode(k, UTF8) -> URLDecoder.decode(v, UTF8))
      case badField => RightBias.left(s"could not parse $badField")
    }
    valuesByPath.map { xs =>
      xs.map {
        case (k, v) => shift(QsPath(k.split('.')))(QsPrimitive(v))
      }.fold(obj())(_ ++ _)
    }
  }

  private def stringifyHelper(qsv: QsValue): Seq[String] = qsv match {
    case QsUndefined | QsNull => Seq("null")
    case QsPrimitive(v) => Seq(URLEncoder.encode(v, UTF8))
    case QsObject(fields) => fields.toSeq.flatMap {
      case (_, QsNull) | (_, QsUndefined) => Seq.empty
      case (seg, QsPrimitive(v)) => Seq(URLEncoder.encode(seg, UTF8) + "=" + URLEncoder.encode(v, UTF8))
      case (seg, nested: QsObject) => stringifyHelper(nested).map(URLEncoder.encode(seg, UTF8) + "." + _)
    }
  }
  def stringify(qsv: QsValue): String = stringifyHelper(qsv).mkString("&")
}

final case class QsError(msg: String)

final case class QsReads[T](reads: QsValue => RightBias[QsError, T]) {
  def map[S](f: T => S): QsReads[S] = QsReads(inp => reads(inp).map(f))
}
object QsReads {
  implicit val qsrFCB: FunctionalCanBuild[QsReads] = new FunctionalCanBuild[QsReads] {
    def apply[A, B](ma: QsReads[A], mb: QsReads[B]): QsReads[A ~ B] = {
      QsReads[A ~ B] { inp =>
        for {
          ar <- ma.reads(inp)
          br <- mb.reads(inp)
        } yield new ~(ar, br)
      }
    }
  }

  implicit val qsrFunctor: Functor[QsReads] = new Functor[QsReads] {
    def fmap[A, B](ma: QsReads[A], f: A => B): QsReads[B] = ma.map(f)
  }
}

class QsWrites[T](val writes: T => QsValue)
class QsOWrites[T](override val writes: T => QsObject) extends QsWrites(writes) {
  def contramap[S](f: S => T): QsOWrites[S] = QsOWrites(f andThen writes)
}

object QsWrites {
  def apply[T](writes: T => QsValue) = new QsWrites(writes)
  implicit def extractFromFormat[T](implicit format: QsFormat[T]): QsWrites[T] = format.writes
}
object QsOWrites {
  def apply[T](writes: T => QsObject) = new QsOWrites[T](writes)
  implicit val qswFCB: FunctionalCanBuild[QsOWrites] = new FunctionalCanBuild[QsOWrites] {
    def apply[A, B](ma: QsOWrites[A], mb: QsOWrites[B]): QsOWrites[A ~ B] = {
      QsOWrites[A ~ B] {
        case a ~ b => ma.writes(a) ++ mb.writes(b)
      }
    }
  }
  implicit val qswFunctor: ContravariantFunctor[QsOWrites] = new ContravariantFunctor[QsOWrites] {
    def contramap[A, B](ma: QsOWrites[A], f: B => A): QsOWrites[B] = ma.contramap(f)
  }
}
