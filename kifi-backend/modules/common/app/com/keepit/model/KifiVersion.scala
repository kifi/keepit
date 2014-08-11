package com.keepit.model

import play.api.mvc.{ JavascriptLitteral, QueryStringBindable }

trait KifiVersion {

  def major: Short
  def minor: Short
  def patch: Short
  def build: Int

  assert(major >= 0 && minor >= 0 && patch >= 0 && build >= 0)

  def compareIt(that: KifiVersion): Int = {
    if (this.major != that.major) {
      this.major - that.major
    } else if (this.minor != that.minor) {
      this.minor - that.minor
    } else if (this.patch != that.patch) {
      this.patch - that.patch
    } else {
      this.build - that.build
    }
  }

  override def toString = {
    Seq(major, minor, patch).mkString(".") + (if (build > 0) "." + build else "")
  }
}

case class KifiIPhoneVersion(major: Short, minor: Short, patch: Short, build: Int = 0) extends KifiVersion with Ordered[KifiIPhoneVersion] {
  def compare(that: KifiIPhoneVersion) = compareIt(that)
}

case class KifiAndroidVersion(major: Short, minor: Short, patch: Short, build: Int = 0) extends KifiVersion with Ordered[KifiAndroidVersion] {
  def compare(that: KifiAndroidVersion) = compareIt(that)
}

case class KifiExtVersion(major: Short, minor: Short, patch: Short, build: Int = 0) extends KifiVersion with Ordered[KifiExtVersion] {
  def compare(that: KifiExtVersion) = compareIt(that)
}

object KifiVersion {
  val R = """(\d{1,5})\.(\d{1,5})\.(\d{1,5})(?:\.(\d{1,9}))?""".r
}

object KifiExtVersion {

  def apply(version: String): KifiExtVersion = {
    version match {
      case KifiVersion.R(major, minor, patch, build) =>
        KifiExtVersion(major.toShort, minor.toShort, patch.toShort, Option(build).map(_.toInt).getOrElse(0))
      case _ =>
        throw new IllegalArgumentException("Invalid kifi ext version: " + version)
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KifiExtVersion] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KifiExtVersion]] = {
      stringBinder.bind(key, params) map {
        case Right(version) => Right(KifiExtVersion(version))
        case _ => Left("Unable to bind a KifiVersion")
      }
    }
    override def unbind(key: String, kifiVersion: KifiExtVersion): String = {
      stringBinder.unbind(key, kifiVersion.toString)
    }
  }

  implicit def litteral = new JavascriptLitteral[KifiExtVersion] {
    def to(value: KifiExtVersion) = value.toString
  }
}

object KifiIPhoneVersion {

  def apply(version: String): KifiIPhoneVersion = {
    version match {
      case KifiVersion.R(major, minor, patch, build) =>
        KifiIPhoneVersion(major.toShort, minor.toShort, patch.toShort, Option(build).map(_.toInt).getOrElse(0))
      case _ =>
        throw new IllegalArgumentException("Invalid kifi iPhone version: " + version)
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KifiIPhoneVersion] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KifiIPhoneVersion]] = {
      stringBinder.bind(key, params) map {
        case Right(version) => Right(KifiIPhoneVersion(version))
        case _ => Left("Unable to bind a KifiIPhoneVersion")
      }
    }
    override def unbind(key: String, kifiVersion: KifiIPhoneVersion): String = {
      stringBinder.unbind(key, kifiVersion.toString)
    }
  }

  implicit def litteral = new JavascriptLitteral[KifiIPhoneVersion] {
    def to(value: KifiIPhoneVersion) = value.toString
  }
}

object KifiAndroidVersion {

  def apply(version: String): KifiAndroidVersion = {
    version match {
      case KifiVersion.R(major, minor, patch, build) =>
        KifiAndroidVersion(major.toShort, minor.toShort, patch.toShort, Option(build).map(_.toInt).getOrElse(0))
      case _ =>
        throw new IllegalArgumentException("Invalid kifi Android version: " + version)
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KifiAndroidVersion] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KifiAndroidVersion]] = {
      stringBinder.bind(key, params) map {
        case Right(version) => Right(KifiAndroidVersion(version))
        case _ => Left("Unable to bind a KifiAndroidVersion")
      }
    }
    override def unbind(key: String, kifiVersion: KifiAndroidVersion): String = {
      stringBinder.unbind(key, kifiVersion.toString)
    }
  }

  implicit def litteral = new JavascriptLitteral[KifiAndroidVersion] {
    def to(value: KifiAndroidVersion) = value.toString
  }
}
