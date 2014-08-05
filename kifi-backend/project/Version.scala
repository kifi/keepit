import java.io.PrintWriter
import java.util.Locale

import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import sbt.Keys._
import sbt._

object Version {
  lazy val UTC = DateTimeZone.UTC
  lazy val BUILD_DATETIME_FORMAT = DateTimeFormat.forPattern("yyyyMMdd-HHmm").withLocale(Locale.ENGLISH).withZone(UTC)
  lazy val buildTime  = BUILD_DATETIME_FORMAT.print(new DateTime(UTC))
  lazy val appVersion = "%s-%s-%s".format(buildTime,"git rev-parse --abbrev-ref HEAD".!!.trim, "git rev-parse --short HEAD".!!.trim)


  val now = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(UTC).print(new DateTime(UTC))

  private def writeToFile(fileName: String, value: String) = {
    val file = new PrintWriter(new File(fileName))
    try { file.print(value) } finally { file.close() }
  }

  def writeVersionToFile() = {
    writeToFile("conf/app_version.txt", appVersion)
    writeToFile("modules/common/conf/app_version.txt", appVersion)
    writeToFile("conf/app_compilation_date.txt", now)
    writeToFile("modules/common/conf/app_compilation_date.txt", now)
  }
}
