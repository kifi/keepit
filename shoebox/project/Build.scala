import sbt._
import Keys._
import play.Project._
import scala.sys.process._
import java.io.PrintWriter
import java.io.File
import java.util.Locale
import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalTime}
import org.joda.time.format.DateTimeFormat
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys

object ApplicationBuild extends Build {

  override def settings = super.settings ++ Seq(
      EclipseKeys.skipParents in ThisBuild := false)
      
    val appName         = "shoebox"
      
    val BUILD_DATETIME_FORMAT = DateTimeFormat.forPattern("yyyyMMdd-HHmm")
                                                 .withLocale(Locale.ENGLISH)
                                                 .withZone(DateTimeZone.forID("America/Los_Angeles"))
    val buildTime = BUILD_DATETIME_FORMAT.print(new DateTime(DateTimeZone.forID("America/Los_Angeles")))
    val appVersion      = "%s-%s-%s".format(buildTime,"git rev-parse --abbrev-ref HEAD".!!.trim, "git rev-parse --short HEAD".!!.trim)
    val PT = DateTimeZone.forID("America/Los_Angeles")
    val now = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(PT).print(new DateTime(PT))

    def writeToFile(fileName: String, value: String) = {
      val file = new PrintWriter(new File(fileName))
      try { file.print(value) } finally { file.close() }
    }

    writeToFile("conf/app_version.txt", appVersion)
    writeToFile("conf/app_compilation_date.txt", now)

    /*
     * play-plugins-util is dependent on guice 2.x while we use guice 3.x.
     * guice 2.x is imported from com.cedarsoft and it screws up the entire runtime!
     * for more info
     * https://github.com/harrah/xsbt/wiki/Library-Management
     * https://github.com/typesafehub/play-plugins/blob/master/guice/project/Build.scala
     * http://stackoverflow.com/questions/10958215/how-to-exclude-commons-logging-from-a-scala-sbt-slf4j-project 
     */
    val appDependencies = Seq(
      "mysql" % "mysql-connector-java" % "5.1.10",
      "org.clapper" %% "grizzled-slf4j" % "1.0.1",
      "com.typesafe.akka" % "akka-testkit" % "2.0.2",
      "org.igniterealtime.smack" % "smackx-debug" % "3.2.1",
      "org.kevoree.extra.xmpp.lib" % "smack" % "3.2.2",
      "org.apache.lucene" % "lucene-core" % "3.6.1",
      "org.apache.lucene" % "lucene-analyzers" % "3.6.1",
      "org.apache.httpcomponents" % "httpclient" % "4.2.1",
      "org.apache.tika" % "tika-parsers" % "1.2",
      "com.cybozu.labs" % "langdetect" % "1.1-20120112",
      //used for securesocial
      "com.typesafe" %% "play-plugins-util" % "2.1",
      "org.mindrot" % "jbcrypt" % "0.3m",
      "com.amazonaws" % "aws-java-sdk" % "1.3.20",
      "javax.mail" % "mail" % "1.4.5",
      "org.mongodb" %% "casbah" % "2.5.0",
      "de.l3s.boilerpipe" % "boilerpipe" % "1.2.0",
      "org.jsoup" % "jsoup" % "1.7.1",
      "spy" % "spymemcached" % "2.8.1"
    ) map (_.excludeAll(ExclusionRule(organization = "com.cedarsoft")))

    val main = play.Project(appName, appVersion, appDependencies).settings(
      // add some imports to the routes file
      routesImport ++= Seq(
        "com.keepit.common.db.{ExternalId, Id, State}",
        "com.keepit.model._",
        "com.keepit.common.social._",
        "com.keepit.search._"
      ),

      resolvers ++= Seq(
        "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
        "kevoree Repository" at "http://maven2.kevoree.org/release/",
        //used for securesocial
        "jBCrypt Repository" at "http://repo1.maven.org/maven2/org/",
        "boilerpipe Repository" at "http://boilerpipe.googlecode.com/svn/repo/",
        "Spy Repository" at "http://files.couchbase.com/maven2",
        //for org.mongodb#casb
        "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
        "releases"  at "https://oss.sonatype.org/content/groups/scala-tools"
      ),
      
      // add some imports to the templates files
      templatesImport ++= Seq(
        "com.keepit.common.db.{ExternalId, Id, State}",
        "com.keepit.model._",
        "com.keepit.common.social._",
        "com.keepit.search._"
      ),
      
      libraryDependencies ++= Seq(
        "com.google.inject" % "guice" % "3.0",
        "com.google.inject.extensions" % "guice-multibindings" % "3.0",
        "com.tzavellas" % "sse-guice" % "0.6.1",
        //"org.scalatest" %% "scalatest" % "2.0.M4" % "test",
        //"com.typesafe" % "slick_2.10" % "1.0.0-RC1"
        "org.scalaquery" % "scalaquery_2.9.1" % "0.10.0-M1"
      )
    )
}
