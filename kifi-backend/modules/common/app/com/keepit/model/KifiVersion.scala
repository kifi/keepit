package com.keepit.model

import play.api.mvc.{ JavascriptLitteral, QueryStringBindable }

trait KifiVersion {

  def major: Short
  def minor: Short
  def patch: Short
  def build: Int

  require(major >= 0 && minor >= -1 && patch >= -1 && build >= -1)
  if (minor == -1) require(patch == -1)
  if (patch == -1) require(build == -1)

  def compareIt(that: KifiVersion): Int = {
    if (this.major != that.major) {
      this.major - that.major
    } else if (this.minor != that.minor && (this.minor > 0 || that.minor > 0)) {
      this.minor - that.minor
    } else if (this.patch != that.patch && (this.patch > 0 || that.patch > 0)) {
      this.patch - that.patch
    } else if (this.build > 0 || that.build > 0) {
      this.build - that.build
    } else {
      0
    }
  }

  override def hashCode: Int = 31 * (31 * (31 * major + math.max(0, minor)) + math.max(0, patch)) + math.max(0, build)

  override def toString = {
    Seq(major) ++ (if (minor >= 0) Some(minor) ++ (if (patch >= 0) Some(patch) ++ (if (build >= 0) Some(build) else Nil) else Nil) else Nil) mkString "."
  }

  def toStringWithoutBuild = {
    Seq(major) ++ (if (minor >= 0) Some(minor) ++ (if (patch >= 0) Some(patch) else Nil) else Nil) mkString "."
  }
}

case class KifiExtVersion(major: Short, minor: Short, patch: Short = -1, build: Int = -1) extends KifiVersion with Ordered[KifiExtVersion] {
  def compare(that: KifiExtVersion): Int = compareIt(that)
  override def equals(that: Any): Boolean = that match {
    case v: KifiExtVersion => compareIt(v) == 0
    case _ => false
  }
}

case class KifiIPhoneVersion(major: Short, minor: Short, patch: Short = -1, build: Int = -1) extends KifiVersion with Ordered[KifiIPhoneVersion] {
  def compare(that: KifiIPhoneVersion): Int = compareIt(that)
  override def equals(that: Any): Boolean = that match {
    case v: KifiIPhoneVersion => compareIt(v) == 0
    case _ => false
  }
}

case class KifiAndroidVersion(major: Short, minor: Short, patch: Short = -1, build: Int = -1) extends KifiVersion with Ordered[KifiAndroidVersion] {
  def compare(that: KifiAndroidVersion): Int = compareIt(that)
  override def equals(that: Any): Boolean = that match {
    case v: KifiAndroidVersion => compareIt(v) == 0
    case _ => false
  }
}

object KifiVersion {
  private val R = """(\d{1,5})(?:\.(\d{1,5})(?:\.(\d{1,5})(?:\.(\d{1,9}))?)?)?""".r

  def parse(version: String): (Short, Short, Short, Int) = {
    version match {
      case R(major, minor, patch, build) =>
        (major.toShort,
          Option(minor).map(_.toShort).getOrElse(-1),
          Option(patch).map(_.toShort).getOrElse(-1),
          Option(build).map(_.toInt).getOrElse(-1))
      case _ =>
        throw new IllegalArgumentException("Invalid version: " + version)
    }
  }
}

object KifiExtVersion {

  def apply(version: String): KifiExtVersion = {
    val (major, minor, patch, build) = KifiVersion.parse(version)
    KifiExtVersion(major, minor, patch, build)
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
    val (major, minor, patch, build) = KifiVersion.parse(version)
    KifiIPhoneVersion(major, minor, patch, build)
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
    val (major, minor, patch, build) = KifiVersion.parse(version)
    KifiAndroidVersion(major, minor, patch, build)
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
