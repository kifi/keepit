import sbt._
import Keys._
import PlayProject._
import scala.sys.process._
import java.io.PrintWriter
import java.io.File
import java.util.Locale
import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalTime}
import org.joda.time.format.DateTimeFormat

object ApplicationBuild extends Build {

    val appName         = "shoebox"
    val appVersion      = "%s-%s".format("git rev-parse --abbrev-ref HEAD".!!.trim, "git rev-parse --short HEAD".!!.trim)
    val PT = DateTimeZone.forID("America/Los_Angeles")
    val now = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(PT).print(new DateTime(PT))

    def writeToFile(fileName: String, value: String) = {
      val file = new PrintWriter(new File(fileName))
      try { file.print(value) } finally { file.close() }
    }

    writeToFile("conf/app_version.txt", appVersion)
    writeToFile("conf/app_compilation_date.txt", now)

    val appDependencies = Seq(
      "ru.circumflex" % "circumflex-orm" % "2.1" % "compile->default",
      "mysql" % "mysql-connector-java" % "5.1.10",
      "org.clapper" %% "grizzled-slf4j" % "0.6.9"
    )

   val ssDependencies = Seq(
      // Add your project dependencies here,
      "com.typesafe" %% "play-plugins-util" % "2.0.1",
      "org.mindrot" % "jbcrypt" % "0.3m"
    )

    val secureSocial = PlayProject(
    	"securesocial", appVersion, ssDependencies, mainLang = SCALA, path = file("modules/securesocial")
    ).settings(
      resolvers ++= Seq(
        "jBCrypt Repository" at "http://repo1.maven.org/maven2/org/",
        "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
      )
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // add some imports to the routes file
      routesImport ++= Seq(
        "com.keepit.common.db.{ExternalId, Id}",
        "com.keepit.model._"
      ),

      resolvers ++= Seq(
        "jBCrypt Repository" at "http://repo1.maven.org/maven2/org/",
        "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
      ),
      
      // add some imports to the templates files
      templatesImport ++= Seq(
        "com.keepit.common.db.{ExternalId, Id}",
        "com.keepit.model._"
      )
    ).dependsOn(secureSocial).aggregate(secureSocial)

}
